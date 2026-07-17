package com.zwm.deviceshare;

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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OnlineService extends Service {
    public static final String ACTION_START = "com.zwm.deviceshare.START";
    public static final String ACTION_STOP = "com.zwm.deviceshare.STOP";
    public static final String ACTION_TASK_READY = "com.zwm.deviceshare.TASK_READY";
    public static final String ACTION_STATUS = "com.zwm.deviceshare.STATUS";

    private static final String TAG = "DeviceShareService";
    private static final String PREFS = "device_share";
    private static final String CHANNEL_ID = "device_share_online";
    private static final int NOTIFICATION_ID = 3401;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("设备在线，等待电脑投送", false));
        if (!running) {
            running = true;
            executor.execute(this::pollLoop);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollLoop() {
        while (running) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean("serviceEnabled", false)) break;
            String serverUrl = prefs.getString("serverUrl", "");
            String token = prefs.getString("token", "");
            String deviceId = prefs.getString("deviceId", "");
            String deviceName = prefs.getString("deviceName", Build.MODEL);
            if (serverUrl.isEmpty() || token.isEmpty() || deviceId.isEmpty()) {
                notifyStatus("配置不完整，请回到 App 重新填写");
                sleep(3000);
                continue;
            }
            try {
                heartbeat(serverUrl, token, deviceId, deviceName);
                if (PendingTaskStore.exists(this)) {
                    notifyStatus("有一批素材等待分享");
                    sleep(2200);
                    continue;
                }
                JSONObject next = getJson(serverUrl + "/api/device/tasks/next?deviceId=" + enc(deviceId), token);
                JSONObject task = next.optJSONObject("task");
                if (task != null) {
                    receiveTask(serverUrl, token, deviceId, task);
                } else {
                    notifyStatus("已连接电脑，等待素材");
                }
            } catch (Exception error) {
                Log.w(TAG, "poll failed", error);
                notifyStatus("连接失败：" + compact(error.getMessage()));
            }
            sleep(2200);
        }
        stopSelf();
    }

    private void heartbeat(String serverUrl, String token, String deviceId, String deviceName) throws Exception {
        JSONObject body = new JSONObject()
                .put("deviceId", deviceId)
                .put("name", deviceName)
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("appVersion", "0.1.0");
        postJson(serverUrl + "/api/device/heartbeat", token, body);
    }

    private void receiveTask(String serverUrl, String token, String deviceId, JSONObject task) {
        String taskId = task.optString("id");
        try {
            postStatus(serverUrl, token, deviceId, taskId, "downloading", null);
            notifyStatus("正在接收素材…");
            File taskDir = new File(new File(getCacheDir(), "share"), taskId);
            deleteRecursively(taskDir);
            if (!taskDir.mkdirs() && !taskDir.isDirectory()) throw new IllegalStateException("无法创建缓存目录");

            JSONArray files = task.getJSONArray("files");
            JSONArray localFiles = new JSONArray();
            for (int i = 0; i < files.length(); i++) {
                JSONObject remote = files.getJSONObject(i);
                String originalName = safeName(remote.optString("name", "file-" + (i + 1)));
                String storedName = String.format(Locale.US, "%03d-%s", i, originalName);
                File output = new File(taskDir, storedName);
                String downloadPath = remote.getString("downloadPath");
                String separator = downloadPath.contains("?") ? "&" : "?";
                download(serverUrl + downloadPath + separator + "deviceId=" + enc(deviceId), token, output, remote.optString("sha256"));
                localFiles.put(new JSONObject()
                        .put("name", originalName)
                        .put("storedName", storedName)
                        .put("mime", remote.optString("mime", "application/octet-stream"))
                        .put("size", output.length()));
            }

            JSONObject pending = new JSONObject()
                    .put("id", taskId)
                    .put("serverUrl", serverUrl)
                    .put("token", token)
                    .put("deviceId", deviceId)
                    .put("text", task.optString("text", ""))
                    .put("files", localFiles);
            PendingTaskStore.write(this, pending);
            postStatus(serverUrl, token, deviceId, taskId, "ready", null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, buildNotification("素材已接收，点击打开分享", true));
            sendBroadcast(new Intent(ACTION_TASK_READY).setPackage(getPackageName()));
        } catch (Exception error) {
            Log.e(TAG, "receive task failed", error);
            try {
                postStatus(serverUrl, token, deviceId, taskId, "failed", compact(error.getMessage()));
            } catch (Exception ignored) {
            }
            notifyStatus("接收失败：" + compact(error.getMessage()));
        }
    }

    private void download(String url, String token, File output, String expectedSha256) throws Exception {
        HttpURLConnection connection = open(url, token, "GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("下载失败 HTTP " + code);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                out.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
        String actual = hex(digest.digest());
        if (!expectedSha256.isEmpty() && !expectedSha256.equalsIgnoreCase(actual)) {
            if (!output.delete()) Log.w(TAG, "failed to delete bad download");
            throw new IllegalStateException("文件校验失败：" + output.getName());
        }
    }

    private JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection connection = open(url, token, "GET");
        try {
            int code = connection.getResponseCode();
            String body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + body);
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject postJson(String url, String token, JSONObject body) throws Exception {
        HttpURLConnection connection = open(url, token, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
            output.write(bytes);
        }
        try {
            int code = connection.getResponseCode();
            String response = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + response);
            return response.isEmpty() ? new JSONObject() : new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    static void postStatus(String serverUrl, String token, String deviceId, String taskId, String status, String error) throws Exception {
        JSONObject body = new JSONObject().put("deviceId", deviceId).put("status", status);
        if (error != null) body.put("error", error);
        postJson(serverUrl + "/api/device/tasks/" + enc(taskId) + "/status", token, body);
    }

    private static HttpURLConnection open(String url, String token, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(60_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json, */*");
        return connection;
    }

    private Notification buildNotification(String text, boolean taskReady) {
        Intent openIntent = new Intent(this, taskReady ? ShareActivity.class : MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                taskReady ? 2 : 1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(taskReady ? "素材等待分享" : "素材投送设备在线")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(!taskReady)
                .setAutoCancel(taskReady)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "素材投送在线服务", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("接收电脑通过局域网投送的图片和视频");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void notifyStatus(String message) {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("message", message));
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("设备不支持 UTF-8", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private static String safeName(String value) {
        String clean = value.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^\\.+", "").trim();
        return clean.isEmpty() ? "file" : clean.substring(0, Math.min(clean.length(), 160));
    }

    private static String compact(String value) {
        if (value == null || value.trim().isEmpty()) return "未知错误";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.substring(0, Math.min(oneLine.length(), 180));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) Log.w(TAG, "failed to delete " + file);
    }
}
