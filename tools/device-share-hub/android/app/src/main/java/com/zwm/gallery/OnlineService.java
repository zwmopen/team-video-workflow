package com.zwm.gallery;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.MediaStore;
import android.net.Uri;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public final class OnlineService extends Service {
    /** Optional discovery capability: the PC may send a signed APK over LAN V2. */
    public static final String UPDATE_CAPABILITY = "apk-push-v1";
    public static final String ACTION_START = "com.zwm.gallery.START";
    public static final String ACTION_STOP = "com.zwm.gallery.STOP";
    public static final String ACTION_TASK_READY = "com.zwm.gallery.TASK_READY";
    public static final String EXTRA_AUTO_SHARE_WORK_ID = "autoShareWorkId";
    public static final String ACTION_STATUS = "com.zwm.gallery.STATUS";
    public static final String ACTION_PEERS_CHANGED = "com.zwm.gallery.PEERS_CHANGED";
    public static final String ACTION_SHARE_OPENED = "com.zwm.gallery.SHARE_OPENED";
    public static final String ACTION_SHARE_FINISHED = "com.zwm.gallery.SHARE_FINISHED";
    public static final String ACTION_CLIPBOARD_CHANGED = "com.zwm.gallery.CLIPBOARD_CHANGED";
    public static final String ACTION_REFRESH_OVERLAY = "com.zwm.gallery.REFRESH_OVERLAY";
    /** Ask the running receiver to publish its current inventory immediately. */
    public static final String ACTION_REFRESH_STATUS = "com.zwm.gallery.REFRESH_STATUS";
    public static final String ACTION_REFRESH_SCREENSHOTS = "com.zwm.gallery.REFRESH_SCREENSHOTS";

    private static final String TAG = "DeviceShareService";
    private static final String PREFS = "device_share";
    private static final String PREF_WORK_COUNT = "advertisedWorkCount";
    private static final String PREF_WORK_COUNT_CONVERSION = "advertisedWorkCountConversion";
    private static final String PREF_WORK_COUNT_TRAFFIC = "advertisedWorkCountTraffic";
    private static final String PREF_WORK_COUNT_UNCATEGORIZED = "advertisedWorkCountUncategorized";
    private static final String PREF_REGISTERED_PEERS = "registeredPeers";
    static final String PREF_OVERLAY_HIDDEN_UNTIL = "clipboardOverlayHiddenUntil";
    private static final String FOREGROUND_CHANNEL_ID = "device_share_online_quiet_v2";
    private static final String ALERT_CHANNEL_ID = "device_share_alerts_v2";
    private static final String SILENT_ALERT_CHANNEL_ID = "device_share_alerts_silent_v1";
    public static final String SCREENSHOT_CHANNEL_ID = "device_share_screenshots_v1";
    private static final int FOREGROUND_NOTIFICATION_ID = 3401;
    private static final int TASK_NOTIFICATION_ID = 3402;
    private static final int TRANSFER_PROGRESS_NOTIFICATION_ID = 3403;
    private static final int HTTP_PORT = 45833;
    private static final int DISCOVERY_PORT = 45834;
    private static final int MAX_FILES = 100;
    private static final long MAX_JSON_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L;
    /** Keep an interrupted large-file task long enough for a retry to resume it. */
    private static final long INCOMING_TASK_IDLE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final String TASK_MANIFEST_NAME = "task.json";
    private static final long PEER_TIMEOUT_MS = 15_000L;
    private static final ConcurrentHashMap<String, PeerDevice> PEERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> SEEN_CLIPBOARD_MESSAGES =
            new ConcurrentHashMap<>();

    private final ExecutorService serviceExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running;
    private volatile String state = "online";
    private volatile String currentTaskId = "";
    private volatile ServerSocket serverSocket;
    private volatile DatagramSocket discoverySocket;
    private volatile boolean beaconRequested;
    private boolean discoveryRecovering;
    private WindowManager overlayManager;
    private Button clipboardOverlay;
    private WindowManager.LayoutParams clipboardOverlayParams;
    private View clipboardPanel;
    private WindowManager.LayoutParams clipboardPanelParams;
    private LinearLayout clipboardHistoryContainer;
    private LinearLayout clipboardPhraseContainer;
    private TextView clipboardPanelStatus;
    private Button clipboardLatestToggle;
    private boolean clipboardLatestExpanded;
    private String clipboardLatestRenderedText = "";
    private final Handler overlayPauseHandler = new Handler(Looper.getMainLooper());
    private final Runnable restoreClipboardOverlay = this::refreshClipboardOverlay;
    private ContentObserver screenshotObserver;
    private long lastScreenshotDate;
    private long lastScreenshotId;
    private final Object taskLock = new Object();
    private IncomingTask activeTask;

    public static List<PeerDevice> peers() {
        long cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS;
        PEERS.entrySet().removeIf(entry -> entry.getValue().lastSeenMs < cutoff);
        ArrayList<PeerDevice> result = new ArrayList<>(PEERS.values());
        result.sort((left, right) -> left.name.compareToIgnoreCase(right.name));
        return result;
    }

    static void publishWorkCount(Context context, int count) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_WORK_COUNT, Math.max(0, count)).apply();
    }

    static void publishWorkInventory(Context context, List<WorkLibrary.WorkEntry> entries) {
        WorkInventoryCounts counts = WorkInventoryCounts.fromEntries(entries);
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_WORK_COUNT, counts.total)
                .putInt(PREF_WORK_COUNT_CONVERSION, counts.conversion)
                .putInt(PREF_WORK_COUNT_TRAFFIC, counts.traffic)
                .putInt(PREF_WORK_COUNT_UNCATEGORIZED, counts.uncategorized)
                .apply();
        // The normal 2.5s beacon and the Windows polling loop remain as fallbacks.
        // This makes a manual/app-triggered refresh visible to the PC immediately.
        requestImmediateBeacon(context);
    }

    public static void requestImmediateBeacon(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, OnlineService.class)
                .setAction(ACTION_REFRESH_STATUS);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException | SecurityException error) {
            // A background refresh may be restricted by the OS. The running service's
            // regular beacon is still authoritative, so this is deliberately best effort.
            DiagnosticLog.write(context, "status_beacon_request_deferred",
                    error.getClass().getSimpleName());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureIdentity();
        try {
            ClipboardDefaults.ensure(new SharedClipboardStore(
                    new File(getFilesDir(), "shared-clipboard")));
        } catch (Exception error) {
            DiagnosticLog.write(this, "clipboard_defaults_failed", compact(error.getMessage()));
        }
        createChannel();
        cleanupExecutor.scheduleWithFixedDelay(this::runCleanup, 1, 1, TimeUnit.MINUTES);
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
        requestExecutor.execute(this::runCleanup);
        if (!running) {
            running = true;
            serviceExecutor.execute(this::httpLoop);
            serviceExecutor.execute(this::discoveryLoop);
        }
        notifyStatus("局域网接收已开启，等待电脑自动发现");
        if (ACTION_REFRESH_OVERLAY.equals(action)) {
            refreshClipboardOverlay();
            refreshClipboardPanelContents();
        } else if (ACTION_REFRESH_STATUS.equals(action)) {
            beaconRequested = true;
        } else if (ACTION_REFRESH_SCREENSHOTS.equals(action)) {
            startScreenshotObserverIfAllowed();
        } else {
            refreshClipboardOverlay();
            startScreenshotObserverIfAllowed();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSockets();
        serviceExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        cleanupExecutor.shutdownNow();
        overlayPauseHandler.removeCallbacksAndMessages(null);
        stopScreenshotObserver();
        removeClipboardOverlay();
        super.onDestroy();
    }

    private void runCleanup() {
        try {
            cleanupStaleIncomingTask();
            processRelayQueue();
            CleanupCoordinator.Result result = CleanupCoordinator.run(this);
            if (result.moved > 0 || result.deleted > 0 || result.cacheEntriesDeleted > 0) {
                DiagnosticLog.write(this, "scheduled_cleanup",
                        "moved=" + result.moved + " deleted=" + result.deleted
                                + " cache=" + result.cacheEntriesDeleted);
                if (MainActivity.isVisible) {
                    sendBroadcast(new Intent(ACTION_TASK_READY).setPackage(getPackageName()));
                }
            }
            if (!result.failure.isEmpty()) {
                DiagnosticLog.write(this, "scheduled_cleanup_failed", compact(result.failure));
            }
        } catch (Exception error) {
            DiagnosticLog.write(this, "library_maintenance_failed", compact(error.getMessage()));
        }
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
                if ("POST".equals(request.method) && "/v2/clipboard".equals(request.path)) {
                    syncClipboard(request, input, output, client.getInetAddress());
                    return;
                }
                if ("POST".equals(request.method) && "/v2/tasks".equals(request.path)) {
                    createTask(request, input, output);
                    return;
                }
                String[] parts = request.path.split("/");
                if (parts.length == 4 && "GET".equals(request.method)
                        && "v2".equals(parts[1]) && "tasks".equals(parts[2])) {
                    taskStatus(parts[3], output);
                    return;
                }
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
                String message = "接收失败：" + compact(error.getMessage());
                notifyStatus(message);
                cancelTransferProgressNotification();
                notifyTransferEvent("接收失败", compact(error.getMessage()), 3404, null);
                OperationLog.add(this, "接收失败", compact(error.getMessage()));
                writeText(output, error.code, error.getMessage());
            } catch (Exception error) {
                Log.w(TAG, "request failed", error);
                DiagnosticLog.write(this, "request_failed", compact(error.getMessage()));
                String message = "接收失败：" + compact(error.getMessage());
                notifyStatus(message);
                cancelTransferProgressNotification();
                notifyTransferEvent("接收失败", compact(error.getMessage()), 3404, null);
                OperationLog.add(this, "接收失败", compact(error.getMessage()));
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
        boolean resumable = body.optBoolean("resume", false);
        String transferKey = body.optString("transferKey", "").trim();
        Map<Integer, IncomingFileSpec> specs = parseFileSpecs(body, fileCount);
        RelayTaskMetadata relay = RelayTaskMetadata.fromJson(body);
        if (!taskId.matches("[A-Za-z0-9._-]{6,100}")) throw new HttpError(400, "taskId 无效");
        if (fileCount < 1 || fileCount > MAX_FILES) throw new HttpError(400, "文件数量无效");
        String ownId = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("deviceId", "");
        if (relay != null) {
            if (relay.expiresAt <= System.currentTimeMillis()) {
                throw new HttpError(410, "中转任务已经过期");
            }
            if ("screenshot".equals(relay.contentKind)
                    && ownId.equals(relay.destinationId)
                    && !getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("screenshotReceiveEnabled", true)) {
                throw new HttpError(403, "主设备已关闭截图接收");
            }
        }
        JSONObject resumed = null;
        synchronized (taskLock) {
            if (activeTask != null) {
                if (resumable && activeTask.matches(taskId, transferKey, fileCount, specs)) {
                    resumed = taskStatusJson(activeTask);
                } else {
                    throw new HttpError(409, "正在接收另一批素材，请稍后重试");
                }
            } else {
                File taskDir = new File(new File(getCacheDir(), "share"), taskId);
                if (resumable) {
                    IncomingTask restored = restoreIncomingTask(taskDir);
                    if (restored != null && restored.matches(taskId, transferKey, fileCount, specs)) {
                        activeTask = restored;
                        currentTaskId = taskId;
                        state = "receiving";
                        resumed = taskStatusJson(restored);
                    }
                }
                if (resumed == null) {
                    deleteRecursively(taskDir);
                    if (!taskDir.mkdirs() && !taskDir.isDirectory()) throw new HttpError(500, "无法创建缓存目录");
                    activeTask = new IncomingTask(
                            taskId, body.optString("text", ""), body.optBoolean("autoShare", false),
                            fileCount, taskDir, System.currentTimeMillis(), relay,
                            transferKey, specs);
                    persistTaskManifest(activeTask);
                    currentTaskId = taskId;
                    state = "receiving";
                }
            }
        }
        if (resumed != null) {
            DiagnosticLog.write(this, "task_resume", taskId);
            notifyStatus("发现未完成传输，等待电脑从断点继续…");
            writeJson(output, 200, resumed);
            return;
        }
        DiagnosticLog.write(this, "task_created", taskId + " files=" + fileCount);
        notifyStatus("正在接收 " + fileCount + " 个文件…");
        notifyTransferProgress(0, "正在接收文件，共 " + fileCount + " 个文件");
        writeJson(output, 201, taskStatusJson(activeTask));
    }

    private void taskStatus(String taskId, OutputStream output) throws Exception {
        IncomingTask task;
        synchronized (taskLock) {
            task = activeTask;
            if (task == null || !task.id.equals(taskId)) {
                task = restoreIncomingTask(new File(new File(getCacheDir(), "share"), taskId));
                if (task != null) {
                    activeTask = task;
                    currentTaskId = task.id;
                    state = "receiving";
                }
            }
        }
        if (task == null) throw new HttpError(404, "任务不存在");
        writeJson(output, 200, taskStatusJson(task));
    }

    private static Map<Integer, IncomingFileSpec> parseFileSpecs(JSONObject body, int fileCount)
            throws Exception {
        Map<Integer, IncomingFileSpec> specs = new HashMap<>();
        JSONArray records = body.optJSONArray("files");
        if (records == null) return specs;
        for (int position = 0; position < records.length(); position++) {
            JSONObject record = records.optJSONObject(position);
            if (record == null) throw new HttpError(400, "断点文件信息无效");
            int index = record.optInt("index", position);
            long size = record.optLong("size", -1L);
            String name = safeName(record.optString("name", "file-" + (index + 1)));
            if (index < 0 || index >= fileCount || size < 0 || size > MAX_FILE_BYTES) {
                throw new HttpError(400, "断点文件信息无效");
            }
            specs.put(index, new IncomingFileSpec(
                    index, name, record.optString("mime", "application/octet-stream"),
                    size, record.optString("sha256", "").trim(),
                    String.format(Locale.US, "%03d-%s", index, name)));
        }
        return specs;
    }

    private IncomingTask restoreIncomingTask(File taskDir) {
        File manifestFile = new File(taskDir, TASK_MANIFEST_NAME);
        if (!manifestFile.isFile()) return null;
        try (FileInputStream input = new FileInputStream(manifestFile)) {
            JSONObject manifest = new JSONObject(new String(
                    readExact(input, manifestFile.length()), StandardCharsets.UTF_8));
            int fileCount = manifest.optInt("fileCount", 0);
            if (fileCount < 1 || fileCount > MAX_FILES) return null;
            Map<Integer, IncomingFileSpec> specs = parseFileSpecs(manifest, fileCount);
            RelayTaskMetadata relay = RelayTaskMetadata.fromJson(manifest);
            IncomingTask task = new IncomingTask(
                    manifest.optString("taskId", taskDir.getName()),
                    manifest.optString("text", ""), manifest.optBoolean("autoShare", false),
                    fileCount, taskDir, manifest.optLong("startedAtMs", System.currentTimeMillis()),
                    relay, manifest.optString("transferKey", ""), specs);
            task.lastActivityAtMs = manifest.optLong("lastActivityAtMs", task.startedAtMs);
            JSONArray records = manifest.optJSONArray("files");
            if (records != null) {
                for (int position = 0; position < records.length(); position++) {
                    JSONObject record = records.optJSONObject(position);
                    if (record == null || !record.optBoolean("complete", false)) continue;
                    int index = record.optInt("index", position);
                    IncomingFileSpec spec = task.specs.get(index);
                    File file = new File(taskDir, record.optString(
                            "stored", spec == null ? "" : spec.storedName));
                    if (spec == null || !file.isFile() || file.length() != spec.size) continue;
                    task.files.put(index, new ReceivedFile(
                            spec.name, spec.storedName, spec.mime, file.length(),
                            record.optString("completedSha256", spec.sha256), file));
                }
            }
            return task;
        } catch (Exception error) {
            Log.w(TAG, "cannot restore incoming task", error);
            return null;
        }
    }

    private JSONObject taskStatusJson(IncomingTask task) throws Exception {
        JSONObject result = new JSONObject()
                .put("taskId", task.id)
                .put("state", "receiving")
                .put("transferKey", task.transferKey)
                .put("fileCount", task.fileCount)
                .put("lastActivityAtMs", task.lastActivityAtMs);
        JSONArray records = new JSONArray();
        if (!task.specs.isEmpty()) {
            for (int index = 0; index < task.fileCount; index++) {
                IncomingFileSpec spec = task.specs.get(index);
                if (spec == null) continue;
                ReceivedFile received = task.files.get(index);
                File partial = new File(task.dir, spec.storedName + ".receiving");
                long receivedBytes = received != null ? received.size
                        : (partial.isFile() ? partial.length() : 0L);
                records.put(new JSONObject()
                        .put("index", index)
                        .put("name", spec.name)
                        .put("size", spec.size)
                        .put("receivedBytes", receivedBytes)
                        .put("complete", received != null)
                        .put("sha256", spec.sha256));
            }
        } else {
            for (Map.Entry<Integer, ReceivedFile> entry : task.files.entrySet()) {
                ReceivedFile received = entry.getValue();
                records.put(new JSONObject()
                        .put("index", entry.getKey())
                        .put("name", received.name)
                        .put("size", received.size)
                        .put("receivedBytes", received.size)
                        .put("complete", true)
                        .put("sha256", received.sha256));
            }
        }
        result.put("files", records);
        return result;
    }

    private void persistTaskManifest(IncomingTask task) {
        try {
            JSONObject manifest = new JSONObject()
                    .put("version", 1)
                    .put("taskId", task.id)
                    .put("text", task.text)
                    .put("autoShare", task.autoShare)
                    .put("fileCount", task.fileCount)
                    .put("startedAtMs", task.startedAtMs)
                    .put("lastActivityAtMs", task.lastActivityAtMs)
                    .put("transferKey", task.transferKey);
            if (task.relay != null) {
                manifest.put("messageId", task.relay.messageId)
                        .put("originId", task.relay.originId)
                        .put("destinationId", task.relay.destinationId)
                        .put("previousHopId", task.relay.previousHopId)
                        .put("contentKind", task.relay.contentKind)
                        .put("expiresAt", task.relay.expiresAt)
                        .put("hopLimit", task.relay.hopLimit);
            }
            JSONArray records = new JSONArray();
            for (int index = 0; index < task.fileCount; index++) {
                IncomingFileSpec spec = task.specs.get(index);
                if (spec == null) continue;
                ReceivedFile received = task.files.get(index);
                File partial = new File(task.dir, spec.storedName + ".receiving");
                records.put(new JSONObject()
                        .put("index", index)
                        .put("name", spec.name)
                        .put("mime", spec.mime)
                        .put("size", spec.size)
                        .put("sha256", spec.sha256)
                        .put("stored", spec.storedName)
                        .put("receivedBytes", received != null ? received.size
                                : (partial.isFile() ? partial.length() : 0L))
                        .put("complete", received != null)
                        .put("completedSha256", received == null ? "" : received.sha256));
            }
            manifest.put("files", records);
            File temporary = new File(task.dir, TASK_MANIFEST_NAME + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            File target = new File(task.dir, TASK_MANIFEST_NAME);
            if (target.exists() && !target.delete()) return;
            if (!temporary.renameTo(target)) Log.w(TAG, "cannot replace task manifest");
        } catch (Exception error) {
            Log.w(TAG, "cannot persist task manifest", error);
        }
    }

    private static long parseHeaderLong(String value, long fallback, String header)
            throws HttpError {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            long result = Long.parseLong(value.trim());
            if (result < 0) throw new NumberFormatException("negative");
            return result;
        } catch (NumberFormatException error) {
            throw new HttpError(400, header + " 无效");
        }
    }

    private static void updateDigest(MessageDigest digest, File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
    }

    private void uploadFile(String taskId, String indexText, HttpRequest request, InputStream input, OutputStream output) throws Exception {
        int index;
        try { index = Integer.parseInt(indexText); } catch (NumberFormatException error) { throw new HttpError(400, "文件序号无效"); }
        if (request.contentLength < 0 || request.contentLength > MAX_FILE_BYTES) throw new HttpError(413, "文件过大");
        IncomingTask task;
        IncomingFileSpec spec;
        synchronized (taskLock) {
            task = activeTask;
            if (task == null || !task.id.equals(taskId)) throw new HttpError(404, "任务不存在");
            if (index < 0 || index >= task.fileCount) throw new HttpError(400, "文件序号越界");
            if (task.files.containsKey(index)) throw new HttpError(409, "文件已经上传");
            spec = task.specs.get(index);
        }

        String encodedName = request.headers.getOrDefault("x-file-name", "file-" + (index + 1));
        String originalName = safeName(URLDecoder.decode(encodedName, StandardCharsets.UTF_8.name()));
        String mime = request.headers.getOrDefault("x-file-mime", "application/octet-stream");
        String expectedSha = request.headers.getOrDefault("x-file-sha256", "").trim();
        long offset = parseHeaderLong(request.headers.get("x-file-offset"), 0L, "X-File-Offset");
        long totalLength = parseHeaderLong(request.headers.get("x-file-length"),
                request.contentLength + offset, "X-File-Length");
        if (totalLength < 0 || totalLength > MAX_FILE_BYTES) throw new HttpError(413, "文件过大");
        if (!isValidResumeRange(offset, totalLength, request.contentLength)) {
            throw new HttpError(409, "断点位置与本次上传长度不匹配");
        }
        if (spec != null) {
            if (spec.size != totalLength || (!spec.sha256.isEmpty() && !spec.sha256.equalsIgnoreCase(expectedSha))) {
                throw new HttpError(409, "断点任务的文件信息不匹配");
            }
            if (!spec.name.equals(originalName)) throw new HttpError(409, "断点任务的文件名不匹配");
        }
        String storedName = String.format(Locale.US, "%03d-%s", index, originalName);
        File temp = new File(task.dir, storedName + ".receiving");
        File target = new File(task.dir, storedName);
        if (offset > 0) {
            if (!temp.isFile() || temp.length() != offset) {
                throw new HttpError(409, "手机端临时文件与断点不匹配");
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long remaining = request.contentLength;
        long received = offset;
        long lastNotice = 0;
        long startMs = System.currentTimeMillis();
        if (offset > 0) updateDigest(digest, temp);
        DiagnosticLog.write(this, "file_receiving", taskId + " #" + (index + 1) + "/" + task.fileCount
                + " " + originalName + " bytes=" + request.contentLength + " offset=" + offset);
        try (FileOutputStream fileOutput = new FileOutputStream(temp, offset > 0)) {
            byte[] buffer = new byte[128 * 1024];
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new HttpError(400, "文件上传中断");
                if (count == 0) continue;
                fileOutput.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                remaining -= count;
                received += count;
                task.lastActivityAtMs = System.currentTimeMillis();
                long now = System.currentTimeMillis();
                if (now - lastNotice >= 700 || remaining == 0) {
                    lastNotice = now;
                    int filePercent = totalLength <= 0 ? 0 : (int) Math.min(100, (received * 100) / totalLength);
                    int overall = Math.min(100, (index * 100 + filePercent) / task.fileCount);
                    String progress = "正在传送文件 " + overall + "%（"
                            + (index + 1) + "/" + task.fileCount + "）";
                    notifyStatus(progress);
                    notifyTransferProgress(overall, progress);
                }
            }
            fileOutput.flush();
            fileOutput.getFD().sync();
        } catch (Exception error) {
            task.lastActivityAtMs = System.currentTimeMillis();
            persistTaskManifest(task);
            DiagnosticLog.write(this, "file_partial", originalName + " bytes=" + temp.length());
            throw error;
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
            task.lastActivityAtMs = System.currentTimeMillis();
            persistTaskManifest(task);
        }
        long elapsedMs = Math.max(1, System.currentTimeMillis() - startMs);
        DiagnosticLog.write(this, "file_received", originalName + " bytes=" + target.length()
                + " ms=" + elapsedMs + " resumedFrom=" + offset);
        notifyStatus("已接收 " + task.files.size() + "/" + task.fileCount + " 个文件");
        writeText(output, 200, "OK");
    }

    private boolean isIncomingUpdatePackage(ReceivedFile file) {
        return file != null && file.file != null && file.file.isFile()
                && file.name.toLowerCase(Locale.US).endsWith(".apk");
    }

    private int stageIncomingUpdatePackage(ReceivedFile source) throws Exception {
        String version = UpdatePackageValidator.archiveVersionName(this, source.file);
        UpdatePackageValidator.validate(this, source.file, version);
        File root = new File(getFilesDir(), "updates");
        if (!root.isDirectory() && !root.mkdirs()) throw new HttpError(500, "无法创建更新缓存目录");
        String fileName = UpdateChecker.updateFileName(version);
        File target = new File(root, fileName);
        File temp = new File(root, fileName + ".incoming");
        if (temp.isFile() && !temp.delete()) throw new HttpError(500, "无法清理未完成的更新包");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source.file));
             FileOutputStream raw = new FileOutputStream(temp);
             BufferedOutputStream output = new BufferedOutputStream(raw)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            raw.getFD().sync();
        }
        if (target.isFile() && !target.delete()) throw new HttpError(500, "无法替换旧的更新包");
        if (!temp.renameTo(target)) throw new HttpError(500, "无法保存已校验的更新包");
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        UpdateDownloadReceiver.markReady(prefs, -1L, version, fileName);
        prefs.edit().remove(UpdateChecker.PREF_DOWNLOAD_RESULT)
                .remove(UpdateChecker.PREF_DOWNLOAD_ERROR).apply();
        UpdateDownloadReceiver.notifyReady(this, version, fileName);
        sendBroadcast(new Intent(UpdateDownloadReceiver.ACTION_UPDATE_READY).setPackage(getPackageName()));
        DiagnosticLog.write(this, "update_package_received", version + " bytes=" + target.length());
        return 1;
    }

    private void commitTask(String taskId, OutputStream output) throws Exception {
        IncomingTask task;
        synchronized (taskLock) {
            task = activeTask;
            if (task == null || !task.id.equals(taskId)) throw new HttpError(404, "任务不存在");
            if (task.files.size() != task.fileCount) throw new HttpError(409, "文件尚未全部上传");
            task.lastActivityAtMs = System.currentTimeMillis();
        }

        String ownId = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("deviceId", "");
        if (task.relay != null && !ownId.equals(task.relay.destinationId)) {
            queueRelayTask(task);
            synchronized (taskLock) {
                if (activeTask != task) throw new HttpError(409, "任务状态已变化");
                activeTask = null;
                currentTaskId = "";
                state = "online";
            }
            writeJson(output, 202, new JSONObject()
                    .put("relayStatus", "queued")
                    .put("messageId", task.relay.messageId));
            requestExecutor.execute(this::processRelayQueue);
            return;
        }

        WorkLibrary library = new WorkLibrary(new File(getFilesDir(), "work-library"));
        int imported = 0;
        int deliveredFiles = 0;
        String autoShareWorkId = "";
        ReceivedFile only = task.fileCount == 1 ? task.files.get(0) : null;
        String treeValue = getSharedPreferences(PREFS, MODE_PRIVATE).getString("libraryTreeUri", "");
        if (isIncomingUpdatePackage(only)) {
            deliveredFiles = stageIncomingUpdatePackage(only);
        } else if (!treeValue.isEmpty()) {
            android.net.Uri tree = android.net.Uri.parse(treeValue);
            java.util.ArrayList<File> directFiles = new java.util.ArrayList<>();
            java.util.ArrayList<String> directNames = new java.util.ArrayList<>();
            java.util.ArrayList<String> directMimes = new java.util.ArrayList<>();
            java.util.ArrayList<File> directImages = new java.util.ArrayList<>();
            int exportedDirectories = 0;
            for (int index = 0; index < task.fileCount; index++) {
                ReceivedFile file = task.files.get(index);
                if (file == null) throw new HttpError(409, "缺少文件 " + index);
                if (isZip(file)) {
                    DocumentTreeExporter.ExportResult exported = DocumentTreeExporter.exportZip(
                            getContentResolver(), tree, file.file, task.id + "-" + index);
                    deliveredFiles += exported.files;
                    exportedDirectories += exported.directories;
                } else {
                    directFiles.add(file.file);
                    directNames.add(file.name);
                    directMimes.add(file.mime);
                    if (WorkRules.isSupportedImage(file.name)) directImages.add(file.file);
                }
            }
            if (!directFiles.isEmpty()) {
                DocumentTreeExporter.ExportResult exported =
                        DocumentTreeExporter.exportBundle(
                                getContentResolver(), tree, "接收-" + task.id,
                                directFiles, directNames, directMimes, task.text);
                deliveredFiles += exported.files;
                exportedDirectories += exported.directories;
            }
            DocumentTreeImporter.ImportResult result = DocumentTreeImporter.importTree(
                    getContentResolver(), tree, library, new File(getCacheDir(), "tree-import-service"));
            imported = result.imported;
            if (task.autoShare && !directImages.isEmpty()) {
                WorkLibrary.WorkEntry matched = library.findByContent(task.text, directImages);
                if (matched != null) autoShareWorkId = matched.id;
            }
            DiagnosticLog.write(this, "tree_export", "files=" + deliveredFiles
                    + " directories=" + exportedDirectories + " works=" + imported);
        } else if (only != null && isZip(only)) {
                imported = WorkArchiveImporter.importZip(only.file, library, task.id);
                if (imported == 0) throw new HttpError(409, "请先在手机设置接收文件夹，再传送通用压缩包");
                deliveredFiles = 1;
        } else {
            java.util.ArrayList<File> images = new java.util.ArrayList<>();
            for (int index = 0; index < task.fileCount; index++) {
                ReceivedFile file = task.files.get(index);
                if (file == null) throw new HttpError(409, "缺少文件 " + index);
                if (WorkRules.isSupportedImage(file.name)) images.add(file.file);
            }
            if (!images.isEmpty()) {
                library.importWork(task.id, "电脑传入的作品", task.text, images, "");
                imported = 1;
                deliveredFiles = task.fileCount;
                if (task.autoShare) autoShareWorkId = task.id;
            } else {
                throw new HttpError(409, "请先在手机设置接收文件夹，再传送通用文件");
            }
        }

        synchronized (taskLock) {
            if (activeTask != task) throw new HttpError(409, "任务状态已变化");
            activeTask = null;
            currentTaskId = "";
            state = "online";
        }
        deleteRecursively(task.dir);
        DiagnosticLog.write(this, "task_committed", taskId + " files=" + deliveredFiles + " works=" + imported);
        publishWorkInventory(this, library.listActive());
        writeText(output, 200, "OK");
        onTaskReady(taskId, deliveredFiles, imported, autoShareWorkId,
                Math.max(1, System.currentTimeMillis() - task.startedAtMs));
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

    private void onTaskReady(String taskId, int deliveredFiles, int imported,
                             String autoShareWorkId, long elapsedMs) {
        String summary = imported > 0
                ? "已收到 " + deliveredFiles + " 个文件，识别 " + imported + " 个新作品"
                : "已收到 " + deliveredFiles + " 个文件";
        summary += "，耗时 " + formatElapsed(elapsedMs);
        cancelTransferProgressNotification();
        notifyStatus(summary);
        notifyTransferEvent("素材已接收", summary, TASK_NOTIFICATION_ID, autoShareWorkId);
        OperationLog.add(this, "接收完成", summary);
        if (MainActivity.isVisible) {
            sendBroadcast(new Intent(ACTION_TASK_READY)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_AUTO_SHARE_WORK_ID, autoShareWorkId));
        }
    }

    private JSONObject deviceInfo() throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONObject workCounts = null;
        if (prefs.contains(PREF_WORK_COUNT_CONVERSION)
                && prefs.contains(PREF_WORK_COUNT_TRAFFIC)
                && prefs.contains(PREF_WORK_COUNT_UNCATEGORIZED)) {
            workCounts = new JSONObject()
                    .put("total", prefs.getInt(PREF_WORK_COUNT, -1))
                    .put("conversion", prefs.getInt(PREF_WORK_COUNT_CONVERSION, 0))
                    .put("traffic", prefs.getInt(PREF_WORK_COUNT_TRAFFIC, 0))
                    .put("uncategorized", prefs.getInt(PREF_WORK_COUNT_UNCATEGORIZED, 0));
        }
        JSONObject info = new JSONObject()
                .put("protocol", 2)
                .put("deviceId", prefs.getString("deviceId", ""))
                .put("name", prefs.getString("deviceName", Build.MANUFACTURER + " " + Build.MODEL))
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("appVersion", installedVersion())
                .put("versionCode", installedVersionCode())
                .put("updateCapability", UPDATE_CAPABILITY)
                .put("relayVersion", 1)
                .put("relayEnabled", true)
                .put("screenshotReceiveEnabled",
                        prefs.getBoolean("screenshotReceiveEnabled", true))
                .put("port", HTTP_PORT)
                .put("state", state)
                .put("workCount", prefs.getInt(PREF_WORK_COUNT, -1))
                .put("taskId", currentTaskId);
        if (workCounts != null) info.put("workCounts", workCounts);
        return info;
    }

    private void queueRelayTask(IncomingTask task) throws Exception {
        File root = new File(getCacheDir(), "relay-queue");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new HttpError(500, "无法创建中转缓存");
        }
        File destination = new File(root, task.relay.messageId);
        deleteRecursively(destination);
        JSONObject manifest = new JSONObject()
                .put("text", task.text)
                .put("messageId", task.relay.messageId)
                .put("originId", task.relay.originId)
                .put("destinationId", task.relay.destinationId)
                .put("previousHopId", task.relay.previousHopId)
                .put("contentKind", task.relay.contentKind)
                .put("expiresAt", task.relay.expiresAt)
                .put("hopLimit", task.relay.hopLimit);
        JSONArray files = new JSONArray();
        for (int index = 0; index < task.fileCount; index++) {
            ReceivedFile file = task.files.get(index);
            if (file == null) throw new HttpError(409, "中转文件不完整");
            files.put(new JSONObject()
                    .put("stored", file.storedName)
                    .put("name", file.name));
        }
        manifest.put("files", files);
        try (FileOutputStream output = new FileOutputStream(
                new File(task.dir, "relay.json"), false)) {
            output.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (!task.dir.renameTo(destination)) {
            throw new HttpError(500, "无法保存中转任务");
        }
        DiagnosticLog.write(this, "relay_queued",
                task.relay.messageId + " to=" + task.relay.destinationId);
        OperationLog.add(this, "截图等待中转", task.relay.destinationId);
    }

    private void processRelayQueue() {
        File root = new File(getCacheDir(), "relay-queue");
        File[] queued = root.listFiles(File::isDirectory);
        if (queued == null) return;
        for (File directory : queued) {
            try {
                processRelayDirectory(directory);
            } catch (Exception error) {
                DiagnosticLog.write(this, "relay_retry_waiting",
                        directory.getName() + " " + compact(error.getMessage()));
            }
        }
    }

    private void processRelayDirectory(File directory) throws Exception {
        File manifestFile = new File(directory, "relay.json");
        if (!manifestFile.isFile()) {
            deleteRecursively(directory);
            return;
        }
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(manifestFile)) {
            bytes = readExact(input, manifestFile.length());
        }
        JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        RelayTaskMetadata relay = new RelayTaskMetadata(
                manifest.optString("messageId", directory.getName()),
                manifest.optString("originId", ""),
                manifest.optString("destinationId", ""),
                manifest.optString("previousHopId", ""),
                manifest.optString("contentKind", "file"),
                manifest.optLong("expiresAt", 0),
                manifest.optInt("hopLimit", 0));
        if (System.currentTimeMillis() >= relay.expiresAt || relay.hopLimit <= 0) {
            deleteRecursively(directory);
            OperationLog.add(this, "中转已过期", relay.destinationId);
            DiagnosticLog.write(this, "relay_expired", relay.messageId);
            return;
        }
        PeerDevice next = null;
        for (PeerDevice peer : peers()) {
            if (peer.id.equals(relay.destinationId)) {
                next = peer;
                break;
            }
        }
        if (next == null) {
            for (PeerDevice peer : peers()) {
                if (!peer.id.equals(relay.previousHopId)
                        && !peer.id.equals(relay.originId)) {
                    next = peer;
                    break;
                }
            }
        }
        if (next == null) throw new IllegalStateException("暂时没有下一跳设备");
        JSONArray records = manifest.getJSONArray("files");
        ArrayList<File> files = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            File file = new File(directory, record.getString("stored"));
            if (!file.isFile()) throw new IllegalStateException("中转缓存文件缺失");
            files.add(file);
            names.add(record.optString("name", file.getName()));
        }
        String currentId = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("deviceId", "");
        RelayTaskMetadata forwarded = relay.forwardedBy(currentId);
        PeerDevice finalNext = next;
        new TransferClient(getContentResolver(), getCacheDir()).sendLocalFiles(
                finalNext, files, names, manifest.optString("text", ""), forwarded,
                (percent, text) -> notifyStatus("正在中转截图 " + percent + "%"));
        deleteRecursively(directory);
        OperationLog.add(this, "中转发送完成", finalNext.name);
        DiagnosticLog.write(this, "relay_delivered",
                relay.messageId + " via=" + finalNext.id);
    }

    private static boolean isZip(ReceivedFile file) {
        return file.name.toLowerCase(Locale.ROOT).endsWith(".zip")
                || "application/zip".equalsIgnoreCase(file.mime)
                || "application/x-zip-compressed".equalsIgnoreCase(file.mime);
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    /**
     * A sender can disappear before it gets a chance to call /cancel. Do not
     * leave the receiver permanently in "receiving" in that case. Only an
     * incomplete task is eligible; once every file arrived, commit is allowed
     * to finish its import without being interrupted by maintenance.
     */
    static boolean isIncomingTaskStale(long now, long lastActivityAtMs,
                                       int receivedFiles, int expectedFiles) {
        return expectedFiles > receivedFiles
                && now >= lastActivityAtMs
                && now - lastActivityAtMs >= INCOMING_TASK_IDLE_TIMEOUT_MS;
    }

    static boolean isValidResumeRange(long offset, long totalLength, long contentLength) {
        return offset >= 0 && totalLength >= 0 && contentLength >= 0
                && offset <= totalLength && contentLength == totalLength - offset;
    }

    private void cleanupStaleIncomingTask() {
        IncomingTask stale = null;
        long now = System.currentTimeMillis();
        synchronized (taskLock) {
            if (activeTask != null && isIncomingTaskStale(
                    now, activeTask.lastActivityAtMs,
                    activeTask.files.size(), activeTask.fileCount)) {
                stale = activeTask;
                activeTask = null;
                currentTaskId = "";
                state = "online";
            }
        }
        if (stale == null) return;
        deleteRecursively(stale.dir);
        cancelTransferProgressNotification();
        String detail = stale.id + " idleMs="
                + Math.max(0L, now - stale.lastActivityAtMs)
                + " files=" + stale.files.size() + "/" + stale.fileCount;
        DiagnosticLog.write(this, "task_expired", detail);
        notifyStatus("上次传输已中断，临时文件已清理，可以重新发送");
        OperationLog.add(this, "传输中断已清理", "已清理未完成任务 " + stale.id);
    }

    private long installedVersionCode() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private void discoveryLoop() {
        DiscoveryRecovery.run(
                () -> running,
                this::runDiscoverySession,
                this::handleDiscoveryFailure,
                Thread::sleep);
    }

    private void syncClipboard(
            HttpRequest request, InputStream input, OutputStream output, InetAddress sourceAddress)
            throws Exception {
        if (request.contentLength < 0 || request.contentLength > 1024L * 1024L) {
            throw new HttpError(413, "剪切板同步数据过大");
        }
        ClipboardSyncPayload.Decoded payload =
                ClipboardSyncPayload.decode(readExact(input, request.contentLength));
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> registered = preferences.getStringSet(
                PREF_REGISTERED_PEERS, Collections.emptySet());
        PeerDevice peer = PEERS.get(payload.senderId);
        String sourceIp = sourceAddress == null ? "" : sourceAddress.getHostAddress();
        if (!registered.contains(payload.senderId)
                || peer == null || !peer.ip.equals(sourceIp)) {
            throw new HttpError(403, "发送设备尚未登记");
        }
        long now = System.currentTimeMillis();
        SEEN_CLIPBOARD_MESSAGES.entrySet().removeIf(
                entry -> entry.getValue() < now - TimeUnit.HOURS.toMillis(1));
        boolean firstSeen = SEEN_CLIPBOARD_MESSAGES.putIfAbsent(
                payload.messageId, now) == null;
        SharedClipboardStore store = new SharedClipboardStore(
                new File(getFilesDir(), "shared-clipboard"));
        int changed = store.merge(payload.items);
        int pruned = store.retainNewestClipboardOnly();
        if (changed > 0 || pruned > 0) {
            writeNewestClipboardToSystem(store);
            sendBroadcast(new Intent(ACTION_CLIPBOARD_CHANGED).setPackage(getPackageName()));
            new Handler(Looper.getMainLooper()).post(this::refreshClipboardPanelContents);
            DiagnosticLog.write(this, "clipboard_received",
                    "peer=" + payload.senderId + " changed=" + changed + " pruned=" + pruned);
        }
        writeJson(output, 200, new JSONObject().put("changed", changed));
        if (firstSeen && payload.hopLimit > 0) {
            requestExecutor.execute(() -> forwardClipboard(payload));
        }
    }

    private void writeNewestClipboardToSystem(SharedClipboardStore store) {
        try {
            SharedClipboardStore.Item newest = store.newestClipboard();
            if (newest == null || newest.deleted || newest.text.trim().isEmpty()) return;
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("共享剪切板", newest.text));
            }
        } catch (Exception error) {
            DiagnosticLog.write(this, "clipboard_system_write_failed",
                    compact(error.getMessage()));
        }
    }

    private void forwardClipboard(ClipboardSyncPayload.Decoded payload) {
        String ownId = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("deviceId", "");
        for (PeerDevice candidate : peers()) {
            if (candidate.id.equals(payload.senderId)
                    || candidate.id.equals(payload.originId)) continue;
            try {
                new TransferClient(getContentResolver(), getCacheDir()).syncClipboard(
                        candidate, ownId, payload.originId, payload.messageId,
                        payload.hopLimit - 1, payload.items);
            } catch (Exception error) {
                DiagnosticLog.write(this, "clipboard_relay_skipped",
                        candidate.name + " " + compact(error.getMessage()));
            }
        }
    }

    private void runDiscoverySession() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            discoverySocket = socket;
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress("0.0.0.0", DISCOVERY_PORT));
            socket.setSoTimeout(900);
            boolean recovered = discoveryRecovering;
            discoveryRecovering = false;
            DiagnosticLog.write(this, recovered ? "discovery_recovered" : "discovery_ready",
                    "udp=" + DISCOVERY_PORT);
            if (recovered) notifyStatus("Wi-Fi 已恢复，正在重新发现设备");
            long nextBeacon = 0;
            byte[] buffer = new byte[2048];
            while (running) {
                long now = System.currentTimeMillis();
                if (beaconRequested || now >= nextBeacon) {
                    beaconRequested = false;
                    sendBeacon(socket, null);
                    nextBeacon = now + 2500;
                }
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    if ("ZWMDS2_DISCOVER".equals(text)) {
                        sendBeacon(socket, new InetSocketAddress(packet.getAddress(), packet.getPort()));
                    } else if (text.startsWith("ZWMDS2_HERE|2|")) {
                        rememberPeer(text, packet.getAddress());
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } finally {
            discoverySocket = null;
        }
    }

    private static String formatElapsed(long elapsedMs) {
        long seconds = Math.max(1, (elapsedMs + 999) / 1000);
        return seconds < 60 ? seconds + " 秒"
                : (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    private void handleDiscoveryFailure(Exception error) {
        discoveryRecovering = true;
        Log.w(TAG, "discovery interrupted; retrying", error);
        DiagnosticLog.write(this, "discovery_retry", compact(error.getMessage()));
        notifyStatus("Wi-Fi 连接刚发生变化，正在自动恢复设备发现");
    }

    private void rememberPeer(String packet, InetAddress address) {
        try {
            String[] parts = packet.split("\\|", -1);
            if (parts.length < 8) return;
            String ownId = getSharedPreferences(PREFS, MODE_PRIVATE).getString("deviceId", "");
            String id = parts[2];
            if (id.isEmpty() || id.equals(ownId)) return;
            int port;
            try { port = Integer.parseInt(parts[3]); } catch (Exception ignored) { port = HTTP_PORT; }
            int workCount = parts.length >= 9 ? PeerDevice.parseWorkCount(parts[8]) : -1;
            long now = System.currentTimeMillis();
            PeerDevice peer = new PeerDevice(id, decodeB64(parts[4]), decodeB64(parts[5]),
                    address.getHostAddress(), port, decodeB64(parts[6]), workCount,
                    now, transportFor(address));
            SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> registeredPeers = new HashSet<>(preferences.getStringSet(
                    PREF_REGISTERED_PEERS, Collections.emptySet()));
            if (registeredPeers.add(id)) {
                preferences.edit().putStringSet(PREF_REGISTERED_PEERS, registeredPeers).apply();
                DiagnosticLog.write(this, "peer_registered", peer.name);
            }
            if (id.startsWith("windows-") || "Windows PC".equals(peer.model)) {
                Set<String> registered = new HashSet<>(preferences.getStringSet("registeredComputers",
                        Collections.emptySet()));
                if (registered.add(id)) {
                    preferences.edit().putStringSet("registeredComputers", registered).apply();
                    DiagnosticLog.write(this, "computer_registered", peer.name);
                    notifyStatus("电脑已确认传送权限");
                }
            }
            PeerDevice previous = PEERS.put(id, peer);
            boolean reappeared = previous == null
                    || previous.lastSeenMs < now - PEER_TIMEOUT_MS;
            boolean changed = !peer.equalsForDisplay(previous);
            if (changed || reappeared) {
                sendBroadcast(new Intent(ACTION_PEERS_CHANGED).setPackage(getPackageName()));
                DiagnosticLog.write(this, reappeared ? "peer_reappeared" : "peer_found",
                        peer.name + " " + peer.ip);
                if (!id.startsWith("windows-") && !"Windows PC".equals(peer.model)) {
                    requestExecutor.execute(() -> pushClipboardToPeer(peer));
                }
            }
        } catch (Exception error) {
            DiagnosticLog.write(this, "peer_packet_invalid", compact(error.getMessage()));
        }
    }

    private void pushClipboardToPeer(PeerDevice peer) {
        try {
            SharedClipboardStore store = new SharedClipboardStore(
                    new File(getFilesDir(), "shared-clipboard"));
            List<SharedClipboardStore.Item> items = store.syncSnapshot();
            if (items.isEmpty()) return;
            String senderId = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("deviceId", "");
            new TransferClient(getContentResolver(), getCacheDir())
                    .syncClipboard(peer, senderId, items);
            DiagnosticLog.write(this, "clipboard_peer_synced", peer.name);
        } catch (Exception error) {
            DiagnosticLog.write(this, "clipboard_peer_sync_skipped",
                    peer.name + " " + compact(error.getMessage()));
        }
    }

    private void refreshClipboardOverlay() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = preferences.getBoolean("clipboardOverlayEnabled", true);
        long now = System.currentTimeMillis();
        long hiddenUntil = preferences.getLong(PREF_OVERLAY_HIDDEN_UNTIL, 0L);
        overlayPauseHandler.removeCallbacks(restoreClipboardOverlay);
        if (enabled && OverlayPausePolicy.isTemporarilyHidden(hiddenUntil, now)) {
            removeClipboardOverlay();
            overlayPauseHandler.postDelayed(restoreClipboardOverlay,
                    Math.min(hiddenUntil - now, OverlayPausePolicy.ONE_DAY_MS));
            return;
        }
        if (hiddenUntil != 0L) {
            preferences.edit().remove(PREF_OVERLAY_HIDDEN_UNTIL).apply();
        }
        if (!enabled || !Settings.canDrawOverlays(this)) {
            removeClipboardOverlay();
            return;
        }
        if (clipboardOverlay != null) return;
        overlayManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (overlayManager == null) return;
        try {
            ClipboardDefaults.ensure(new SharedClipboardStore(
                    new File(getFilesDir(), "shared-clipboard")));
        } catch (Exception error) {
            DiagnosticLog.write(this, "clipboard_defaults_failed", compact(error.getMessage()));
        }
        OverlayButton button = new OverlayButton(this);
        button.setText("剪");
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription("打开共享剪切板");
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(54, 105, 72));
        background.setStroke(dp(1), Color.argb(100, 255, 255, 255));
        button.setBackground(background);
        button.setElevation(dp(6));
        button.setOnClickListener(v -> toggleClipboardPanel());
        clipboardOverlayParams = new WindowManager.LayoutParams(
                dp(52), dp(52),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        clipboardOverlayParams.gravity = Gravity.START | Gravity.TOP;
        clipboardOverlayParams.x = clamp(
                preferences.getInt("clipboardOverlayX", screenWidth() - dp(64)),
                0, Math.max(0, screenWidth() - dp(52)));
        clipboardOverlayParams.y = clamp(
                preferences.getInt("clipboardOverlayYV2", screenHeight() / 4),
                0, Math.max(0, screenHeight() - dp(52)));
        button.setOnTouchListener(new android.view.View.OnTouchListener() {
            float startRawX;
            float startRawY;
            int startX;
            int startY;
            boolean moved;
            boolean holdTriggered;
            final Runnable holdAction = () -> {
                if (moved || clipboardOverlay != button) return;
                holdTriggered = true;
                showOverlayPauseChoices();
            };
            @Override public boolean onTouch(android.view.View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startX = clipboardOverlayParams.x;
                    startY = clipboardOverlayParams.y;
                    moved = false;
                    holdTriggered = false;
                    view.postDelayed(holdAction, 5_000L);
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int deltaX = Math.round(event.getRawX() - startRawX);
                    int deltaY = Math.round(event.getRawY() - startRawY);
                    if (Math.abs(deltaX) > dp(4) || Math.abs(deltaY) > dp(4)) {
                        moved = true;
                        view.removeCallbacks(holdAction);
                    }
                    clipboardOverlayParams.x = clamp(startX + deltaX,
                            0, Math.max(0, screenWidth() - dp(52)));
                    clipboardOverlayParams.y = clamp(startY + deltaY,
                            0, Math.max(0, screenHeight() - dp(52)));
                    try { overlayManager.updateViewLayout(button, clipboardOverlayParams); }
                    catch (Exception ignored) { }
                    return moved;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP && moved) {
                    view.removeCallbacks(holdAction);
                    preferences.edit()
                            .putInt("clipboardOverlayX", clipboardOverlayParams.x)
                            .putInt("clipboardOverlayYV2", clipboardOverlayParams.y)
                            .apply();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    view.removeCallbacks(holdAction);
                    if (holdTriggered) return true;
                    view.performClick();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(holdAction);
                    return true;
                }
                return true;
            }
        });
        try {
            overlayManager.addView(button, clipboardOverlayParams);
            clipboardOverlay = button;
            DiagnosticLog.write(this, "clipboard_overlay_shown", "enabled");
        } catch (Exception error) {
            DiagnosticLog.write(this, "clipboard_overlay_failed", compact(error.getMessage()));
        }
    }

    private void removeClipboardOverlay() {
        removeClipboardPanel();
        if (overlayManager != null && clipboardOverlay != null) {
            try { overlayManager.removeView(clipboardOverlay); }
            catch (Exception ignored) { }
        }
        clipboardOverlay = null;
        clipboardOverlayParams = null;
    }

    private void showOverlayPauseChoices() {
        if (!Settings.canDrawOverlays(this)) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("关闭悬浮球多久？")
                .setMessage("临时关闭到期会自动恢复；永久关闭后可在相册设置中重新开启。")
                .setItems(new String[]{"1 分钟", "30 分钟", "永久关闭"},
                        (whichDialog, which) -> {
                            if (which == 2) pauseClipboardOverlayPermanently();
                            else {
                                long[] durations = {
                                        OverlayPausePolicy.ONE_MINUTE_MS,
                                        OverlayPausePolicy.THIRTY_MINUTES_MS};
                                pauseClipboardOverlayFor(durations[which]);
                            }
                        })
                .setNegativeButton("取消", null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        try { dialog.show(); }
        catch (Exception error) {
            DiagnosticLog.write(this, "clipboard_overlay_pause_dialog_failed",
                    compact(error.getMessage()));
        }
    }

    private void pauseClipboardOverlayFor(long durationMs) {
        long until = OverlayPausePolicy.hiddenUntil(System.currentTimeMillis(), durationMs);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(PREF_OVERLAY_HIDDEN_UNTIL, until)
                .apply();
        removeClipboardOverlay();
        overlayPauseHandler.removeCallbacks(restoreClipboardOverlay);
        overlayPauseHandler.postDelayed(restoreClipboardOverlay,
                Math.min(durationMs, OverlayPausePolicy.ONE_DAY_MS));
    }

    private void pauseClipboardOverlayPermanently() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("clipboardOverlayEnabled", false)
                .remove(PREF_OVERLAY_HIDDEN_UNTIL)
                .apply();
        overlayPauseHandler.removeCallbacks(restoreClipboardOverlay);
        removeClipboardOverlay();
    }

    private void toggleClipboardPanel() {
        if (clipboardPanel == null) showClipboardPanel();
        else removeClipboardPanel();
    }

    private void showClipboardPanel() {
        if (overlayManager == null || clipboardPanel != null) return;
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int minimumWidth = dp(250);
        int minimumHeight = dp(280);
        int maximumWidth = Math.max(minimumWidth, screenWidth() - dp(16));
        int maximumHeight = Math.max(minimumHeight, screenHeight() - dp(32));
        int defaultWidth = clamp(screenWidth() / 2, minimumWidth, maximumWidth);
        int defaultHeight = clamp(screenHeight() / 2, minimumHeight, maximumHeight);
        int width = clamp(preferences.getInt("clipboardPanelWidth",
                defaultWidth), minimumWidth, maximumWidth);
        int height = clamp(preferences.getInt("clipboardPanelHeight",
                defaultHeight), minimumHeight, maximumHeight);

        FrameLayout shell = new FrameLayout(this);
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.rgb(246, 244, 240));
        panelBackground.setCornerRadius(dp(18));
        panelBackground.setStroke(dp(2), Color.rgb(74, 112, 90));
        shell.setBackground(panelBackground);
        shell.setElevation(dp(12));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(16));
        shell.addView(body, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, 0, dp(8));
        TextView title = overlayText("共享剪切板", 16, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button add = overlayButton("＋");
        add.setContentDescription("新增固定常用语");
        add.setOnClickListener(v -> {
            removeClipboardPanel();
            startActivity(new Intent(this, ClipboardActivity.class)
                    .putExtra(ClipboardActivity.EXTRA_ADD_PHRASE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        header.addView(add, new LinearLayout.LayoutParams(dp(42), dp(40)));
        Button close = overlayButton("×");
        close.setContentDescription("关闭共享剪切板");
        close.setOnClickListener(v -> removeClipboardPanel());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(42), dp(40));
        closeParams.setMargins(dp(5), 0, 0, 0);
        header.addView(close, closeParams);
        body.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout latestHeader = new LinearLayout(this);
        latestHeader.setOrientation(LinearLayout.HORIZONTAL);
        latestHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView latestHeading = overlayText("最新剪切", 13, true);
        latestHeading.setPadding(dp(2), dp(2), dp(2), dp(5));
        latestHeader.addView(latestHeading, new LinearLayout.LayoutParams(0, dp(36), 1));
        clipboardLatestToggle = overlayButton("展开");
        clipboardLatestToggle.setContentDescription("展开或收起最新剪切内容");
        clipboardLatestToggle.setVisibility(View.GONE);
        clipboardLatestToggle.setOnClickListener(v -> {
            clipboardLatestExpanded = !clipboardLatestExpanded;
            refreshClipboardPanelContents();
        });
        latestHeader.addView(clipboardLatestToggle,
                new LinearLayout.LayoutParams(dp(58), dp(34)));
        body.addView(latestHeader, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout scrollingContent = new LinearLayout(this);
        scrollingContent.setOrientation(LinearLayout.VERTICAL);
        clipboardHistoryContainer = new LinearLayout(this);
        clipboardHistoryContainer.setOrientation(LinearLayout.VERTICAL);
        scrollingContent.addView(clipboardHistoryContainer,
                new LinearLayout.LayoutParams(-1, -2));

        TextView phraseHeading = overlayText("固定常用语", 13, true);
        phraseHeading.setPadding(dp(2), dp(8), dp(2), dp(5));
        scrollingContent.addView(phraseHeading, new LinearLayout.LayoutParams(-1, dp(40)));
        clipboardPhraseContainer = new LinearLayout(this);
        clipboardPhraseContainer.setOrientation(LinearLayout.VERTICAL);
        scrollingContent.addView(clipboardPhraseContainer,
                new LinearLayout.LayoutParams(-1, -2));
        ScrollView contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        contentScroll.addView(scrollingContent,
                new ScrollView.LayoutParams(-1, -2));
        body.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        clipboardPanelStatus = overlayText("点击一条即可复制", 11, false);
        clipboardPanelStatus.setGravity(Gravity.CENTER);
        clipboardPanelStatus.setTextColor(Color.rgb(72, 105, 84));
        body.addView(clipboardPanelStatus, new LinearLayout.LayoutParams(-1, dp(32)));

        TextView resize = overlayText("↘", 24, true);
        resize.setGravity(Gravity.CENTER);
        resize.setTextColor(Color.rgb(54, 105, 72));
        resize.setContentDescription("拖动调整剪切板窗口大小");
        FrameLayout.LayoutParams resizeParams = new FrameLayout.LayoutParams(dp(42), dp(42),
                Gravity.END | Gravity.BOTTOM);
        shell.addView(resize, resizeParams);

        clipboardPanelParams = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        clipboardPanelParams.gravity = Gravity.START | Gravity.TOP;
        clipboardPanelParams.x = clamp(preferences.getInt("clipboardPanelX",
                Math.max(0, (screenWidth() - width) / 2)),
                0, Math.max(0, screenWidth() - width));
        clipboardPanelParams.y = clamp(preferences.getInt("clipboardPanelY",
                dp(8)), 0, Math.max(0, screenHeight() - height));
        shell.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                removeClipboardPanel();
                return true;
            }
            return false;
        });

        header.setOnTouchListener(new View.OnTouchListener() {
            float rawX;
            float rawY;
            int startX;
            int startY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    rawX = event.getRawX();
                    rawY = event.getRawY();
                    startX = clipboardPanelParams.x;
                    startY = clipboardPanelParams.y;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    clipboardPanelParams.x = clamp(startX + Math.round(event.getRawX() - rawX),
                            0, Math.max(0, screenWidth() - clipboardPanelParams.width));
                    clipboardPanelParams.y = clamp(startY + Math.round(event.getRawY() - rawY),
                            0, Math.max(0, screenHeight() - clipboardPanelParams.height));
                    updateClipboardPanelLayout();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    saveClipboardPanelBounds();
                    return true;
                }
                return true;
            }
        });
        resize.setOnTouchListener(new View.OnTouchListener() {
            float rawX;
            float rawY;
            int startWidth;
            int startHeight;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    rawX = event.getRawX();
                    rawY = event.getRawY();
                    startWidth = clipboardPanelParams.width;
                    startHeight = clipboardPanelParams.height;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    clipboardPanelParams.width = clamp(
                            startWidth + Math.round(event.getRawX() - rawX),
                            minimumWidth, Math.max(minimumWidth,
                                    screenWidth() - clipboardPanelParams.x));
                    clipboardPanelParams.height = clamp(
                            startHeight + Math.round(event.getRawY() - rawY),
                            minimumHeight, Math.max(minimumHeight,
                                    screenHeight() - clipboardPanelParams.y));
                    updateClipboardPanelLayout();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    saveClipboardPanelBounds();
                    return true;
                }
                return true;
            }
        });
        try {
            overlayManager.addView(shell, clipboardPanelParams);
            clipboardPanel = shell;
            // Android 10+ only exposes another app's clipboard to the currently focused app.
            // Let the focusable overlay settle before reading after the user's explicit tap.
            shell.postDelayed(() -> {
                if (clipboardPanel == shell) captureCurrentClipboardAndSync();
            }, 180L);
            DiagnosticLog.write(this, "clipboard_panel_shown", "floating_resizable");
        } catch (Exception error) {
            clipboardPanelParams = null;
            clipboardHistoryContainer = null;
            clipboardPhraseContainer = null;
            clipboardPanelStatus = null;
            clipboardLatestToggle = null;
            clipboardLatestExpanded = false;
            clipboardLatestRenderedText = "";
            DiagnosticLog.write(this, "clipboard_panel_failed", compact(error.getMessage()));
        }
    }

    private void refreshClipboardPanelContents() {
        if (clipboardPanel == null || clipboardHistoryContainer == null
                || clipboardPhraseContainer == null) return;
        requestExecutor.execute(() -> {
            try {
                SharedClipboardStore store = new SharedClipboardStore(
                        new File(getFilesDir(), "shared-clipboard"));
                ClipboardDefaults.ensure(store);
                List<SharedClipboardStore.Item> history =
                        store.visible(SharedClipboardStore.KIND_CLIPBOARD);
                List<SharedClipboardStore.Item> phrases =
                        store.visible(SharedClipboardStore.KIND_PHRASE);
                new Handler(Looper.getMainLooper()).post(() ->
                        renderClipboardPanel(history, phrases));
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (clipboardPanelStatus != null) {
                        clipboardPanelStatus.setText("读取失败：" + compact(error.getMessage()));
                    }
                });
            }
        });
    }

    private void captureCurrentClipboardAndSync() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = manager == null ? null : manager.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0
                ? null : clip.getItemAt(0).coerceToText(this);
        String text = value == null ? "" : value.toString().trim();
        captureClipboardTextAndSync(text);
    }

    private void captureClipboardTextAndSync(String text) {
        requestExecutor.execute(() -> {
            try {
                SharedClipboardStore store = new SharedClipboardStore(
                        new File(getFilesDir(), "shared-clipboard"));
                ClipboardDefaults.ensure(store);
                if (!text.isEmpty()) {
                    SharedClipboardStore.Item newest = store.newestClipboard();
                    if (newest == null || !text.equals(newest.text)) {
                        store.add(getSharedPreferences(PREFS, MODE_PRIVATE)
                                        .getString("deviceId", "device"),
                                SharedClipboardStore.KIND_CLIPBOARD,
                                text, store.nextTimestamp(System.currentTimeMillis()));
                    }
                    store.retainNewestClipboardOnly();
                    List<SharedClipboardStore.Item> snapshot = store.syncSnapshot();
                    int synced = 0;
                    for (PeerDevice peer : peers()) {
                        if (peer.id.startsWith("windows-") || "Windows PC".equals(peer.model)) {
                            continue;
                        }
                        try {
                            new TransferClient(getContentResolver(), getCacheDir())
                                    .syncClipboard(peer,
                                            getSharedPreferences(PREFS, MODE_PRIVATE)
                                                    .getString("deviceId", ""),
                                            snapshot);
                            synced++;
                        } catch (Exception error) {
                            DiagnosticLog.write(this, "clipboard_sync_failed",
                                    peer.name + " " + compact(error.getMessage()));
                        }
                    }
                    int finalSynced = synced;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        refreshClipboardPanelContents();
                        if (clipboardPanelStatus != null) {
                            clipboardPanelStatus.setText(finalSynced == 0
                                    ? "最新剪切已保存 · 暂无其他在线手机"
                                    : "最新剪切已同步到 " + finalSynced + " 台在线手机");
                        }
                    });
                } else {
                    new Handler(Looper.getMainLooper()).post(
                            this::refreshClipboardPanelContents);
                }
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (clipboardPanelStatus != null) {
                        clipboardPanelStatus.setText("同步失败：" + compact(error.getMessage()));
                    }
                });
            }
        });
    }

    private void renderClipboardPanel(List<SharedClipboardStore.Item> history,
                                      List<SharedClipboardStore.Item> phrases) {
        if (clipboardHistoryContainer == null || clipboardPhraseContainer == null) return;
        clipboardHistoryContainer.removeAllViews();
        clipboardPhraseContainer.removeAllViews();
        String latestText = history.isEmpty() ? "" : history.get(0).text;
        if (!latestText.equals(clipboardLatestRenderedText)) {
            clipboardLatestRenderedText = latestText;
            clipboardLatestExpanded = false;
        }
        boolean collapsible = ClipboardDisplayPolicy.shouldCollapse(latestText);
        if (clipboardLatestToggle != null) {
            clipboardLatestToggle.setVisibility(collapsible ? View.VISIBLE : View.GONE);
            clipboardLatestToggle.setText(clipboardLatestExpanded ? "收起" : "展开");
        }
        if (history.isEmpty()) addOverlayEmpty(clipboardHistoryContainer, "暂无记录");
        else {
            Button latest = overlayItem(latestText);
            latest.setMaxLines(clipboardLatestExpanded ? Integer.MAX_VALUE
                    : ClipboardDisplayPolicy.COLLAPSED_LINES);
            latest.setEllipsize(clipboardLatestExpanded
                    ? null : android.text.TextUtils.TruncateAt.END);
            clipboardHistoryContainer.addView(latest, overlayItemParams());
        }
        if (phrases.isEmpty()) addOverlayEmpty(clipboardPhraseContainer, "暂无常用语");
        else for (SharedClipboardStore.Item item : phrases) {
            clipboardPhraseContainer.addView(overlayItem(item.text), overlayItemParams());
        }
        if (clipboardPanelStatus != null) {
            clipboardPanelStatus.setText("最新剪切 " + (history.isEmpty() ? 0 : 1)
                    + " 条 · 固定常用语 " + phrases.size() + " 条 · 点击复制");
        }
    }

    private Button overlayItem(String value) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(12);
        button.setTextColor(Color.rgb(38, 40, 37));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), Color.rgb(220, 218, 212));
        button.setBackground(background);
        button.setOnClickListener(v -> copyOverlayText(value));
        return button;
    }

    private LinearLayout.LayoutParams overlayItemParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(6));
        return params;
    }

    private void addOverlayEmpty(LinearLayout target, String value) {
        TextView empty = overlayText(value, 12, false);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.GRAY);
        target.addView(empty, new LinearLayout.LayoutParams(-1, dp(72)));
    }

    private void copyOverlayText(String value) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(
                ClipData.newPlainText("共享剪切板", value));
        if (clipboardPanelStatus != null) clipboardPanelStatus.setText("已复制，正在同步…");
        captureClipboardTextAndSync(value == null ? "" : value.trim());
    }

    private TextView overlayText(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(35, 35, 33));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button overlayButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setPadding(0, 0, 0, 0);
        button.setTextColor(Color.rgb(45, 55, 48));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(232, 235, 229));
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        return button;
    }

    private void updateClipboardPanelLayout() {
        if (overlayManager == null || clipboardPanel == null || clipboardPanelParams == null) return;
        try { overlayManager.updateViewLayout(clipboardPanel, clipboardPanelParams); }
        catch (Exception ignored) { }
    }

    private void saveClipboardPanelBounds() {
        if (clipboardPanelParams == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("clipboardPanelX", clipboardPanelParams.x)
                .putInt("clipboardPanelY", clipboardPanelParams.y)
                .putInt("clipboardPanelWidth", clipboardPanelParams.width)
                .putInt("clipboardPanelHeight", clipboardPanelParams.height)
                .apply();
    }

    private void removeClipboardPanel() {
        if (overlayManager != null && clipboardPanel != null) {
            try { overlayManager.removeView(clipboardPanel); }
            catch (Exception ignored) { }
        }
        clipboardPanel = null;
        clipboardPanelParams = null;
        clipboardHistoryContainer = null;
        clipboardPhraseContainer = null;
        clipboardPanelStatus = null;
        clipboardLatestToggle = null;
        clipboardLatestExpanded = false;
        clipboardLatestRenderedText = "";
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(Math.max(minimum, maximum), value));
    }

    private void startScreenshotObserverIfAllowed() {
        stopScreenshotObserver();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean("screenshotSyncEnabled", false)
                || !hasScreenshotPermission()) {
            return;
        }
        ScreenshotRow latest = queryLatestImage();
        lastScreenshotDate = latest == null
                ? System.currentTimeMillis() / 1000L : latest.dateAdded;
        lastScreenshotId = latest == null ? 0 : latest.id;
        screenshotObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                requestExecutor.execute(() -> detectLatestScreenshot(uri));
            }
            @Override public void onChange(boolean selfChange) {
                requestExecutor.execute(() -> detectLatestScreenshot(null));
            }
        };
        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver);
        DiagnosticLog.write(this, "screenshot_observer_started",
                preferences.getString("screenshotTargetPeerId", ""));
    }

    private void stopScreenshotObserver() {
        if (screenshotObserver == null) return;
        try { getContentResolver().unregisterContentObserver(screenshotObserver); }
        catch (Exception ignored) { }
        screenshotObserver = null;
    }

    private boolean hasScreenshotPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void detectLatestScreenshot(Uri changedUri) {
        ScreenshotRow row = queryLatestImage();
        if (row == null
                || row.dateAdded < lastScreenshotDate
                || (row.dateAdded == lastScreenshotDate && row.id <= lastScreenshotId)) {
            return;
        }
        lastScreenshotDate = row.dateAdded;
        lastScreenshotId = row.id;
        if (!ScreenshotDetector.isScreenshot(row.name, row.relativePath)) return;
        showScreenshotPrompt(row.uri);
    }

    private ScreenshotRow queryLatestImage() {
        String[] projection = Build.VERSION.SDK_INT >= 29
                ? new String[]{
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.RELATIVE_PATH,
                        MediaStore.Images.Media.DATE_ADDED}
                : new String[]{
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DATE_ADDED};
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            long id = cursor.getLong(0);
            return new ScreenshotRow(id,
                    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            Long.toString(id)),
                    cursor.getString(1), cursor.getString(2), cursor.getLong(3));
        } catch (Exception error) {
            DiagnosticLog.write(this, "screenshot_query_failed", compact(error.getMessage()));
            return null;
        }
    }

    private void showScreenshotPrompt(Uri uri) {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String peerId = preferences.getString("screenshotTargetPeerId", "");
        String peerName = preferences.getString("screenshotTargetPeerName", "主设备");
        boolean autoSend = preferences.getBoolean("screenshotAutoSendEnabled", true);
        if (peerId.isEmpty() || !autoSend) {
            preferences.edit().putString("pendingScreenshotUri", uri.toString()).apply();
            PendingIntent open = PendingIntent.getActivity(
                    this, 43, new Intent(this, ClipboardActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification pending = new Notification.Builder(this, SCREENSHOT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("发现一张新截图")
                    .setContentText("打开相册选择要发送到的设备")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            getSystemService(NotificationManager.class)
                    .notify(ScreenshotSendReceiver.NOTIFICATION_ID, pending);
            return;
        }
        Intent send = new Intent(this, ScreenshotSendReceiver.class)
                .setAction(ScreenshotSendReceiver.ACTION_SEND)
                .putExtra(ScreenshotSendReceiver.EXTRA_URI, uri.toString())
                .putExtra(ScreenshotSendReceiver.EXTRA_PEER_ID, peerId);
        sendBroadcast(send);
        DiagnosticLog.write(this, "screenshot_auto_send", peerName);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ensureIdentity() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.getString("deviceId", "").isEmpty()) return;
        String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        String stable = androidId == null || androidId.isEmpty()
                ? UUID.randomUUID().toString() : androidId;
        preferences.edit()
                .putString("deviceId", "android-" + stable)
                .putString("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                .apply();
    }

    private static String decodeB64(String value) {
        if (value == null || value.isEmpty()) return "";
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }

    private void sendBeacon(DatagramSocket socket, InetSocketAddress directTarget) throws Exception {
        JSONObject info = deviceInfo();
        String beacon = "ZWMDS2_HERE|2|" + info.getString("deviceId") + "|" + HTTP_PORT + "|"
                + b64(info.getString("name")) + "|" + b64(info.getString("model")) + "|"
                + b64(info.getString("state")) + "|" + info.optString("taskId", "") + "|"
                + info.optInt("workCount", -1) + "|"
                + b64(info.optString("appVersion", "")) + "|"
                + info.optLong("versionCode", -1L) + "|"
                + b64(info.optString("updateCapability", ""));
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

    private String transportFor(InetAddress remote) {
        if (remote == null || remote.getAddress().length != 4) return "WiFi";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress candidate : network.getInterfaceAddresses()) {
                    InetAddress local = candidate.getAddress();
                    if (local == null || local.getAddress().length != 4) continue;
                    if (!sameSubnet(local.getAddress(), remote.getAddress(),
                            candidate.getNetworkPrefixLength())) continue;
                    String name = network.getName() == null ? "" :
                            network.getName().toLowerCase(Locale.US);
                    if (isUsbNetworkInterface(name)) return "USB";
                }
            }
        } catch (Exception ignored) {
        }
        return "WiFi";
    }

    static boolean isUsbNetworkInterface(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(Locale.US);
        return value.contains("rndis") || value.startsWith("usb")
                || value.contains("ncm") || value.contains("tether");
    }

    static boolean sameSubnet(byte[] left, byte[] right, int prefixLength) {
        if (left == null || right == null || left.length != right.length
                || prefixLength < 0 || prefixLength > left.length * 8) return false;
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (left[index] != right[index]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (left[fullBytes] & mask) == (right[fullBytes] & mask);
    }

    private Notification buildForegroundNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, FOREGROUND_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("素材投送接收端在线")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private Notification buildTaskNotification(String channelId, String title, String text, String autoShareWorkId) {
        Intent target = autoShareWorkId == null || autoShareWorkId.isEmpty()
                ? new Intent(this, MainActivity.class)
                : new Intent(this, ShareActivity.class).putExtra(ShareActivity.EXTRA_WORK_ID, autoShareWorkId);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                2,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
    }

    private void cancelTaskNotification() {
        getSystemService(NotificationManager.class).cancel(TASK_NOTIFICATION_ID);
    }

    private Notification buildTransferProgressNotification(int percent, String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 3, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, SILENT_ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("相册正在接收文件")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setProgress(100, Math.max(0, Math.min(100, percent)), false)
                .setOngoing(true)
                .build();
    }

    private void notifyTransferProgress(int percent, String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(TRANSFER_PROGRESS_NOTIFICATION_ID,
                    buildTransferProgressNotification(percent, text));
        }
    }

    private void cancelTransferProgressNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(TRANSFER_PROGRESS_NOTIFICATION_ID);
    }

    private void createChannel() {
        NotificationChannel foreground = new NotificationChannel(
                FOREGROUND_CHANNEL_ID, "局域网接收服务", NotificationManager.IMPORTANCE_LOW);
        foreground.setDescription("保持同一 Wi-Fi 下的设备发现与接收，不响铃、不震动");
        foreground.setSound(null, null);
        foreground.enableVibration(false);

        NotificationChannel alerts = new NotificationChannel(
                ALERT_CHANNEL_ID, "文件接收提醒", NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription("接收完成和失败提醒；声音由设置控制");
        alerts.enableVibration(false);

        NotificationChannel silentAlerts = new NotificationChannel(
                SILENT_ALERT_CHANNEL_ID, "文件接收进度", NotificationManager.IMPORTANCE_LOW);
        silentAlerts.setDescription("显示接收进度，不响铃、不震动");
        silentAlerts.setSound(null, null);
        silentAlerts.enableVibration(false);

        NotificationChannel screenshots = new NotificationChannel(
                SCREENSHOT_CHANNEL_ID, "截图发送提醒", NotificationManager.IMPORTANCE_DEFAULT);
        screenshots.setDescription("截图后询问是否发送到主设备");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(foreground);
        manager.createNotificationChannel(alerts);
        manager.createNotificationChannel(silentAlerts);
        manager.createNotificationChannel(screenshots);
    }

    private void notifyTransferEvent(String title, String text, int notificationId, String autoShareWorkId) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String channel = prefs.getBoolean("soundNotificationsEnabled", false)
                ? ALERT_CHANNEL_ID : SILENT_ALERT_CHANNEL_ID;
        getSystemService(NotificationManager.class).notify(
                notificationId, buildTaskNotification(channel, title, text, autoShareWorkId));
        if (prefs.getBoolean("vibrationEnabled", false)) vibrateOnce();
    }

    private void vibrateOnce() {
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(180);
        }
    }

    private void notifyStatus(String message) {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("message", message));
        if (running) getSystemService(NotificationManager.class)
                .notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(message));
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
        final boolean autoShare;
        final int fileCount;
        final File dir;
        final long startedAtMs;
        volatile long lastActivityAtMs;
        final RelayTaskMetadata relay;
        final String transferKey;
        final Map<Integer, IncomingFileSpec> specs;
        final Map<Integer, ReceivedFile> files = new HashMap<>();

        IncomingTask(
                String id, String text, boolean autoShare, int fileCount,
                File dir, long startedAtMs, RelayTaskMetadata relay,
                String transferKey, Map<Integer, IncomingFileSpec> specs) {
            this.id = id;
            this.text = text;
            this.autoShare = autoShare;
            this.fileCount = fileCount;
            this.dir = dir;
            this.startedAtMs = startedAtMs;
            this.lastActivityAtMs = startedAtMs;
            this.relay = relay;
            this.transferKey = transferKey == null ? "" : transferKey;
            this.specs = specs == null ? new HashMap<>() : new HashMap<>(specs);
        }

        boolean matches(String taskId, String key, int count,
                        Map<Integer, IncomingFileSpec> expected) {
            if (!id.equals(taskId) || fileCount != count) return false;
            if (key != null && !key.isEmpty() && !transferKey.isEmpty()
                    && !transferKey.equals(key)) return false;
            if (expected == null || expected.isEmpty()) return true;
            if (specs.size() != expected.size()) return false;
            for (Map.Entry<Integer, IncomingFileSpec> entry : expected.entrySet()) {
                IncomingFileSpec actual = specs.get(entry.getKey());
                if (actual == null || !actual.samePayload(entry.getValue())) return false;
            }
            return true;
        }
    }

    private static final class IncomingFileSpec {
        final int index;
        final String name;
        final String mime;
        final long size;
        final String sha256;
        final String storedName;

        IncomingFileSpec(int index, String name, String mime, long size,
                         String sha256, String storedName) {
            this.index = index;
            this.name = name;
            this.mime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256;
            this.storedName = storedName;
        }

        boolean samePayload(IncomingFileSpec other) {
            return other != null && index == other.index && size == other.size
                    && name.equals(other.name)
                    && (sha256.isEmpty() || other.sha256.isEmpty() || sha256.equalsIgnoreCase(other.sha256));
        }
    }

    private static final class ScreenshotRow {
        final long id;
        final Uri uri;
        final String name;
        final String relativePath;
        final long dateAdded;

        ScreenshotRow(long id, Uri uri, String name, String relativePath, long dateAdded) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.relativePath = relativePath;
            this.dateAdded = dateAdded;
        }
    }

    private static final class OverlayButton extends Button {
        OverlayButton(Context context) {
            super(context);
        }

        @Override public boolean performClick() {
            return super.performClick();
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
