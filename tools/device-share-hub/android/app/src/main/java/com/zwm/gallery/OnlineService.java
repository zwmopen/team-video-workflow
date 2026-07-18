package com.zwm.gallery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OnlineService extends Service {
    public static final String ACTION_START = "com.zwm.gallery.START";
    public static final String ACTION_STOP = "com.zwm.gallery.STOP";
    public static final String ACTION_TASK_READY = "com.zwm.gallery.TASK_READY";
    public static final String ACTION_STATUS = "com.zwm.gallery.STATUS";
    public static final String ACTION_SHARE_OPENED = "com.zwm.gallery.SHARE_OPENED";
    public static final String ACTION_SHARE_FINISHED = "com.zwm.gallery.SHARE_FINISHED";

    private static final String TAG = "DeviceShareService";
    private static final String PREFS = "device_share";
    private static final String CHANNEL_ID = "device_share_online";
    private static final int FOREGROUND_NOTIFICATION_ID = 3401;
    private static final int TASK_NOTIFICATION_ID = 3402;
    private static final int HTTP_PORT = 45833;
    private static final int DISCOVERY_PORT = 45834;
    private static final int MAX_FILES = 100;
    private static final long MAX_JSON_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L;

    private final ExecutorService serviceExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(4);
    private volatile boolean running;
    private volatile String state = "online";
    private volatile String currentTaskId = "";
    private volatile ServerSocket serverSocket;
    private volatile DatagramSocket discoverySocket;
    private final Object taskLock = new Object();
    private IncomingTask activeTask;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            DiagnosticLog.write(this, "service_stop", "receiver stopped");
            stopReceiver();
            return START_NOT_STICKY;
        }
        if (ACTION_SHARE_OPENED.equals(action)) {
            DiagnosticLog.write(this, "share_opened", intent == null ? "" : intent.getStringExtra("workId"));
            cancelTaskNotification();
            return START_STICKY;
        }
        if (ACTION_SHARE_FINISHED.equals(action)) {
            DiagnosticLog.write(this, "share_finished", intent == null ? "" : intent.getStringExtra("workId"));
            state = "online";
            currentTaskId = "";
            notifyStatus("局域网接收已开启");
            return START_STICKY;
        }

        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification("局域网接收已开启"));
        DiagnosticLog.write(this, "service_start", "receiver foreground service started");
        try {
            new WorkLibrary(new File(getFilesDir(), "work-library")).maintain(java.time.LocalDate.now());
        } catch (Exception error) {
            DiagnosticLog.write(this, "library_maintenance_failed", compact(error.getMessage()));
        }
        if (!running) {
            running = true;
            serviceExecutor.execute(this::httpLoop);
            serviceExecutor.execute(this::discoveryLoop);
        }
        notifyStatus("局域网接收已开启，等待电脑自动发现");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSockets();
        serviceExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopReceiver() {
        running = false;
        closeSockets();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void closeSockets() {
        ServerSocket http = serverSocket;
        if (http != null) {
            try { http.close(); } catch (Exception ignored) { }
        }
        DatagramSocket udp = discoverySocket;
        if (udp != null) udp.close();
    }

    private void httpLoop() {
        try (ServerSocket server = new ServerSocket()) {
            serverSocket = server;
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", HTTP_PORT));
            DiagnosticLog.write(this, "http_ready", "port=" + HTTP_PORT);
            while (running) {
                try {
                    Socket socket = server.accept();
                    socket.setSoTimeout(60_000);
                    requestExecutor.execute(() -> handleHttp(socket));
                } catch (Exception error) {
                    if (running) Log.w(TAG, "accept failed", error);
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "http server failed", error);
            DiagnosticLog.write(this, "http_failed", compact(error.getMessage()));
            notifyStatus("接收端口启动失败：" + compact(error.getMessage()));
        } finally {
            serverSocket = null;
        }
    }

    private void handleHttp(Socket socket) {
        try (Socket client = socket;
             BufferedInputStream input = new BufferedInputStream(client.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            try {
                HttpRequest request = HttpRequest.read(input);
                DiagnosticLog.write(this, "http_request", request.method + " " + request.path + " bytes=" + request.contentLength);
                if ("GET".equals(request.method) && "/v2/info".equals(request.path)) {
                    writeJson(output, 200, deviceInfo());
                    return;
                }
                if ("POST".equals(request.method) && "/v2/tasks".equals(request.path)) {
                    createTask(request, input, output);
                    return;
                }
                String[] parts = request.path.split("/");
                if (parts.length == 6 && "PUT".equals(request.method) && "v2".equals(parts[1]) && "tasks".equals(parts[2]) && "files".equals(parts[4])) {
                    uploadFile(parts[3], parts[5], request, input, output);
                    return;
                }
                if (parts.length == 5 && "POST".equals(request.method) && "v2".equals(parts[1]) && "tasks".equals(parts[2]) && "commit".equals(parts[4])) {
                    commitTask(parts[3], output);
                    return;
                }
                if (parts.length == 5 && "POST".equals(request.method) && "v2".equals(parts[1]) && "tasks".equals(parts[2]) && "cancel".equals(parts[4])) {
                    cancelTask(parts[3], output);
                    return;
                }
                writeText(output, 404, "Not Found");
            } catch (HttpError error) {
                DiagnosticLog.write(this, "http_error", error.code + " " + compact(error.getMessage()));
                writeText(output, error.code, error.getMessage());
            } catch (Exception error) {
                Log.w(TAG, "request failed", error);
                DiagnosticLog.write(this, "request_failed", compact(error.getMessage()));
                writeText(output, 500, compact(error.getMessage()));
            }
        } catch (Exception error) {
            Log.w(TAG, "connection failed", error);
        }
    }

    private void createTask(HttpRequest request, InputStream input, OutputStream output) throws Exception {
        if (request.contentLength < 0 || request.contentLength > MAX_JSON_BYTES) throw new HttpError(413, "任务信息过大");
        JSONObject body = new JSONObject(new String(readExact(input, request.contentLength), StandardCharsets.UTF_8));
        String taskId = body.optString("taskId", "").trim();
        int fileCount = body.optInt("fileCount", 0);
        if (!taskId.matches("[A-Za-z0-9._-]{6,100}")) throw new HttpError(400, "taskId 无效");
        if (fileCount < 1 || fileCount > MAX_FILES) throw new HttpError(400, "文件数量无效");
        synchronized (taskLock) {
            if (activeTask != null) throw new HttpError(409, "正在接收另一批素材，请稍后重试");
            File taskDir = new File(new File(getCacheDir(), "share"), taskId);
            deleteRecursively(taskDir);
            if (!taskDir.mkdirs() && !taskDir.isDirectory()) throw new HttpError(500, "无法创建缓存目录");
            activeTask = new IncomingTask(taskId, body.optString("text", ""), fileCount, taskDir);
            currentTaskId = taskId;
            state = "receiving";
        }
        DiagnosticLog.write(this, "task_created", taskId + " files=" + fileCount);
        notifyStatus("正在接收 " + fileCount + " 个文件…");
        writeText(output, 201, "OK");
    }

    private void uploadFile(String taskId, String indexText, HttpRequest request, InputStream input, OutputStream output) throws Exception {
        int index;
        try { index = Integer.parseInt(indexText); } catch (NumberFormatException error) { throw new HttpError(400, "文件序号无效"); }
        if (request.contentLength < 0 || request.contentLength > MAX_FILE_BYTES) throw new HttpError(413, "文件过大");
        IncomingTask task;
        synchronized (taskLock) {
            task = activeTask;
            if (task == null || !task.id.equals(taskId)) throw new HttpError(404, "任务不存在");
            if (index < 0 || index >= task.fileCount) throw new HttpError(400, "文件序号越界");
            if (task.files.containsKey(index)) throw new HttpError(409, "文件已经上传");
        }

        String encodedName = request.headers.getOrDefault("x-file-name", "file-" + (index + 1));
        String originalName = safeName(URLDecoder.decode(encodedName, StandardCharsets.UTF_8.name()));
        String mime = request.headers.getOrDefault("x-file-mime", "application/octet-stream");
        String expectedSha = request.headers.getOrDefault("x-file-sha256", "").trim();
        String storedName = String.format(Locale.US, "%03d-%s", index, originalName);
        File temp = new File(task.dir, storedName + ".receiving");
        File target = new File(task.dir, storedName);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long remaining = request.contentLength;
        long received = 0;
        long lastNotice = 0;
        long startMs = System.currentTimeMillis();
        DiagnosticLog.write(this, "file_receiving", taskId + " #" + (index + 1) + "/" + task.fileCount + " " + originalName + " bytes=" + request.contentLength);
        try (FileOutputStream fileOutput = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[128 * 1024];
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new HttpError(400, "文件上传中断");
                if (count == 0) continue;
                fileOutput.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                remaining -= count;
                received += count;
                long now = System.currentTimeMillis();
                if (now - lastNotice >= 700 || remaining == 0) {
                    lastNotice = now;
                    int percent = request.contentLength <= 0 ? 0 : (int) Math.min(100, (received * 100) / request.contentLength);
                    notifyStatus("正在接收第 " + (index + 1) + "/" + task.fileCount + " 个文件：" + percent + "%");
                }
            }
            fileOutput.flush();
            fileOutput.getFD().sync();
        }
        String actualSha = hex(digest.digest());
        if (!expectedSha.isEmpty() && !expectedSha.equalsIgnoreCase(actualSha)) {
            temp.delete();
            DiagnosticLog.write(this, "sha256_failed", originalName + " expected=" + expectedSha + " actual=" + actualSha);
            throw new HttpError(400, "SHA-256 校验失败");
        }
        if (target.exists() && !target.delete()) throw new HttpError(500, "无法替换缓存文件");
        if (!temp.renameTo(target)) throw new HttpError(500, "无法保存缓存文件");
        synchronized (taskLock) {
            if (activeTask == null || !activeTask.id.equals(taskId)) throw new HttpError(409, "任务状态已变化");
            task.files.put(index, new ReceivedFile(originalName, storedName, mime, target.length(), actualSha, target));
        }
        long elapsedMs = Math.max(1, System.currentTimeMillis() - startMs);
        DiagnosticLog.write(this, "file_received", originalName + " bytes=" + target.length() + " ms=" + elapsedMs);
        notifyStatus("已接收 " + task.files.size() + "/" + task.fileCount + " 个文件");
        writeText(output, 200, "OK");
    }

    private void commitTask(String taskId, OutputStream output) throws Exception {
        IncomingTask task;
        synchronized (taskLock) {
            task = activeTask;
            if (task == null || !task.id.equals(taskId)) throw new HttpError(404, "任务不存在");
            if (task.files.size() != task.fileCount) throw new HttpError(409, "文件尚未全部上传");
        }

        WorkLibrary library = new WorkLibrary(new File(getFilesDir(), "work-library"));
        int imported;
        ReceivedFile only = task.fileCount == 1 ? task.files.get(0) : null;
        if (only != null && (only.name.toLowerCase(Locale.ROOT).endsWith(".zip")
                || "application/zip".equalsIgnoreCase(only.mime)
                || "application/x-zip-compressed".equalsIgnoreCase(only.mime))) {
            imported = WorkArchiveImporter.importZip(only.file, library, task.id);
        } else {
            java.util.ArrayList<File> images = new java.util.ArrayList<>();
            for (int index = 0; index < task.fileCount; index++) {
                ReceivedFile file = task.files.get(index);
                if (file == null) throw new HttpError(409, "缺少文件 " + index);
                if (WorkRules.isSupportedImage(file.name)) images.add(file.file);
            }
            if (images.isEmpty()) throw new HttpError(400, "这批素材中没有支持的图片");
            library.importWork(task.id, "电脑传入的作品", task.text, images, "");
            imported = 1;
        }

        synchronized (taskLock) {
            if (activeTask != task) throw new HttpError(409, "任务状态已变化");
            activeTask = null;
            currentTaskId = "";
            state = "online";
        }
        deleteRecursively(task.dir);
        DiagnosticLog.write(this, "task_committed", taskId + " works=" + imported);
        writeText(output, 200, "OK");
        onTaskReady(taskId, imported);
    }

    private void cancelTask(String taskId, OutputStream output) throws Exception {
        synchronized (taskLock) {
            if (activeTask != null && activeTask.id.equals(taskId)) {
                deleteRecursively(activeTask.dir);
                activeTask = null;
                currentTaskId = "";
                state = "online";
                DiagnosticLog.write(this, "task_cancelled", taskId);
            }
        }
        writeText(output, 200, "OK");
    }

    private void onTaskReady(String taskId, int imported) {
        notifyStatus("已收到 " + imported + " 个作品");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(TASK_NOTIFICATION_ID, buildTaskNotification());
        if (MainActivity.isVisible) {
            sendBroadcast(new Intent(ACTION_TASK_READY).setPackage(getPackageName()));
        }
    }

    private JSONObject deviceInfo() throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return new JSONObject()
                .put("protocol", 2)
                .put("deviceId", prefs.getString("deviceId", ""))
                .put("name", prefs.getString("deviceName", Build.MANUFACTURER + " " + Build.MODEL))
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("appVersion", "0.3.0")
                .put("port", HTTP_PORT)
                .put("state", state)
                .put("taskId", currentTaskId);
    }

    private void discoveryLoop() {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            discoverySocket = socket;
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress("0.0.0.0", DISCOVERY_PORT));
            socket.setSoTimeout(900);
            DiagnosticLog.write(this, "discovery_ready", "udp=" + DISCOVERY_PORT);
            long nextBeacon = 0;
            byte[] buffer = new byte[2048];
            while (running) {
                long now = System.currentTimeMillis();
                if (now >= nextBeacon) {
                    sendBeacon(socket, null);
                    nextBeacon = now + 2500;
                }
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    if ("ZWMDS2_DISCOVER".equals(text)) {
                        sendBeacon(socket, new InetSocketAddress(packet.getAddress(), packet.getPort()));
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception error) {
            if (running) {
                Log.e(TAG, "discovery failed", error);
                DiagnosticLog.write(this, "discovery_failed", compact(error.getMessage()));
                notifyStatus("局域网发现启动失败：" + compact(error.getMessage()));
            }
        } finally {
            discoverySocket = null;
        }
    }

    private void sendBeacon(DatagramSocket socket, InetSocketAddress directTarget) throws Exception {
        JSONObject info = deviceInfo();
        String beacon = "ZWMDS2_HERE|2|" + info.getString("deviceId") + "|" + HTTP_PORT + "|"
                + b64(info.getString("name")) + "|" + b64(info.getString("model")) + "|"
                + b64(info.getString("state")) + "|" + info.optString("taskId", "");
        byte[] bytes = beacon.getBytes(StandardCharsets.UTF_8);
        if (directTarget != null) {
            socket.send(new DatagramPacket(bytes, bytes.length, directTarget));
            return;
        }
        for (InetAddress address : broadcastAddresses()) {
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, address, DISCOVERY_PORT));
            } catch (Exception ignored) {
            }
        }
    }

    private Iterable<InetAddress> broadcastAddresses() {
        java.util.LinkedHashSet<InetAddress> result = new java.util.LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) result.add(address.getBroadcast());
                }
            }
        } catch (Exception ignored) {
        }
        try { result.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        return result;
    }

    private Notification buildForegroundNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("素材投送接收端在线")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private Notification buildTaskNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                2,
                new Intent(this, ShareActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("素材已接收")
                .setContentText("点击打开安卓系统分享")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
    }

    private void cancelTaskNotification() {
        getSystemService(NotificationManager.class).cancel(TASK_NOTIFICATION_ID);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "素材投送接收服务", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("在局域网接收电脑投送的图片和视频");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void notifyStatus(String message) {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("message", message));
    }

    private static byte[] readExact(InputStream input, long length) throws Exception {
        if (length > Integer.MAX_VALUE) throw new HttpError(413, "请求体过大");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) length);
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new HttpError(400, "请求体不完整");
            if (count == 0) continue;
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return output.toByteArray();
    }

    private static void writeJson(OutputStream output, int code, JSONObject value) throws Exception {
        writeResponse(output, code, value.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    private static void writeText(OutputStream output, int code, String value) throws Exception {
        writeResponse(output, code, value.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    private static void writeResponse(OutputStream output, int code, byte[] body, String contentType) throws Exception {
        String reason = code >= 200 && code < 300 ? "OK" : "Error";
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeName(String value) {
        StringBuilder builder = new StringBuilder();
        String forbidden = "\\/:*?\"<>|";
        for (int index = 0; index < value.length() && builder.length() < 160; index++) {
            char character = value.charAt(index);
            builder.append(character < 32 || forbidden.indexOf(character) >= 0 ? '_' : character);
        }
        String clean = builder.toString().trim();
        int first = 0;
        while (first < clean.length() && clean.charAt(first) == '.') first++;
        clean = clean.substring(first).trim();
        return clean.isEmpty() ? "file" : clean;
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private static String compact(String value) {
        if (value == null || value.trim().isEmpty()) return "未知错误";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.substring(0, Math.min(oneLine.length(), 180));
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) Log.w(TAG, "failed to delete " + file);
    }

    private static final class IncomingTask {
        final String id;
        final String text;
        final int fileCount;
        final File dir;
        final Map<Integer, ReceivedFile> files = new HashMap<>();

        IncomingTask(String id, String text, int fileCount, File dir) {
            this.id = id;
            this.text = text;
            this.fileCount = fileCount;
            this.dir = dir;
        }
    }

    private static final class ReceivedFile {
        final String name;
        final String storedName;
        final String mime;
        final long size;
        final String sha256;
        final File file;

        ReceivedFile(String name, String storedName, String mime, long size, String sha256, File file) {
            this.name = name;
            this.storedName = storedName;
            this.mime = mime;
            this.size = size;
            this.sha256 = sha256;
            this.file = file;
        }
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> headers;
        final long contentLength;

        HttpRequest(String method, String path, Map<String, String> headers, long contentLength) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.contentLength = contentLength;
        }

        static HttpRequest read(InputStream input) throws Exception {
            String requestLine = readLine(input, 8192);
            String[] first = requestLine.split(" ");
            if (first.length < 2) throw new HttpError(400, "请求行无效");
            Map<String, String> headers = new HashMap<>();
            while (true) {
                String line = readLine(input, 16_384);
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) throw new HttpError(400, "请求头无效");
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }
            long length = 0;
            String value = headers.get("content-length");
            if (value != null && !value.isEmpty()) {
                try { length = Long.parseLong(value); } catch (NumberFormatException error) { throw new HttpError(400, "Content-Length 无效"); }
            }
            return new HttpRequest(first[0].toUpperCase(Locale.US), first[1], headers, length);
        }

        private static String readLine(InputStream input, int max) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int previous = -1;
            while (output.size() <= max) {
                int current = input.read();
                if (current < 0) throw new HttpError(400, "连接提前结束");
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = output.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
                }
                output.write(current);
                previous = current;
            }
            throw new HttpError(431, "请求头过长");
        }
    }

    private static final class HttpError extends Exception {
        final int code;
        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
