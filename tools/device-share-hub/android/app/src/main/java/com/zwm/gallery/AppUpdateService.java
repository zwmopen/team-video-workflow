package com.zwm.gallery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppUpdateService extends Service {
    private static final String EXTRA_VERSION = "version";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_SHA256 = "sha256";
    private static final String CHANNEL = "device_share_update_download_v3";
    private static final int NOTIFICATION_ID = 4401;
    private static final int FAILURE_NOTIFICATION_ID = 4404;
    private static final int MAX_REDIRECTS = 5;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static void start(Context context, String version, String url, String sha256) {
        if (!RUNNING.compareAndSet(false, true)) return;
        Intent intent = new Intent(context, AppUpdateService.class)
                .putExtra(EXTRA_VERSION, version).putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_SHA256, sha256 == null ? "" : sha256.trim());
        try { context.startForegroundService(intent); }
        catch (RuntimeException error) { RUNNING.set(false); throw error; }
    }

    static void deletePartial(Context context, String version) {
        File part = new File(new File(context.getFilesDir(), "updates"),
                UpdateChecker.updateFileName(version) + ".part");
        if (part.isFile() && !part.delete())
            DiagnosticLog.write(context, "update_partial_delete_failed", part.getName());
    }

    @Override public void onCreate() { super.onCreate(); ensureChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String version = intent == null ? "" : intent.getStringExtra(EXTRA_VERSION);
        String url = intent == null ? "" : intent.getStringExtra(EXTRA_URL);
        String sha256 = intent == null ? "" : intent.getStringExtra(EXTRA_SHA256);
        startForeground(NOTIFICATION_ID, progressNotification(version, 0, 0));
        if (version == null || version.trim().isEmpty() || url == null || url.trim().isEmpty()) {
            recordFailure("更新信息不完整", false); RUNNING.set(false); stopForeground(true);
            stopSelf(startId); return START_NOT_STICKY;
        }
        worker.execute(() -> {
            try { download(version.trim(), url.trim(), sha256 == null ? "" : sha256.trim()); }
            catch (VerificationFailure error) {
                deletePartial(this, version); recordFailure(safe(error.getMessage()), true);
                notifyFailure("安装包校验失败，点开相册重新下载");
            } catch (Exception error) {
                recordFailure(safe(error.getMessage()), false);
                notifyFailure("下载中断，进度已保留；点开相册可继续");
            } finally { RUNNING.set(false); stopForeground(true); stopSelf(startId); }
        });
        return START_NOT_STICKY;
    }

    private void download(String version, String url, String expectedSha256) throws Exception {
        if (!expectedSha256.matches("(?i)[0-9a-f]{64}"))
            throw new VerificationFailure("发布信息缺少有效的 SHA-256");
        File root = new File(getFilesDir(), "updates");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建更新缓存目录");
        String fileName = UpdateChecker.updateFileName(version);
        File part = new File(root, fileName + ".part");
        File target = new File(root, fileName);
        long existing = part.isFile() ? part.length() : 0L;
        HttpURLConnection connection = open(url, existing);
        int response = connection.getResponseCode();
        if (response == 416 && existing > 0) connection.disconnect();
        else {
            boolean append = shouldAppend(existing, response, connection.getHeaderField("Content-Range"));
            if (response != 200 && response != 206) { connection.disconnect(); throw new IllegalStateException("下载服务返回 " + response); }
            if (existing > 0 && response == 206 && !append) {
                connection.disconnect(); if (!part.delete()) throw new IllegalStateException("断点信息不一致且无法重置");
                throw new IllegalStateException("断点信息不一致，请重新点击下载");
            }
            if (response == 200) existing = 0L;
            long contentLength = connection.getContentLengthLong();
            long total = contentLength > 0 ? existing + contentLength : 0L, downloaded = existing, lastNotify = 0L;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part, append)) {
                byte[] buffer = new byte[128 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue; output.write(buffer, 0, count); downloaded += count;
                    long now = System.currentTimeMillis(); if (now - lastNotify >= 500L) { notifyProgress(version, downloaded, total); lastNotify = now; }
                }
                output.flush(); output.getFD().sync();
            } finally { connection.disconnect(); }
        }
        try {
            String actual; try (InputStream input = new FileInputStream(part)) { actual = UpdateDownloadReceiver.sha256(input); }
            if (!actual.equalsIgnoreCase(expectedSha256)) throw new VerificationFailure("SHA-256 与发布记录不一致");
            UpdatePackageValidator.validate(this, part, version);
        } catch (VerificationFailure error) { if (part.isFile()) part.delete(); throw error; }
          catch (Exception error) { if (part.isFile()) part.delete(); throw new VerificationFailure(safe(error.getMessage())); }
        if (target.isFile() && !target.delete()) throw new IllegalStateException("无法替换旧安装包");
        if (!part.renameTo(target)) throw new IllegalStateException("无法保存已校验安装包");
        cleanupOldPackages(root, target);
        SharedPreferences prefs = getSharedPreferences("device_share", MODE_PRIVATE);
        UpdateDownloadReceiver.markReady(prefs, -1L, version, fileName);
        prefs.edit().remove(UpdateChecker.PREF_DOWNLOAD_RESULT).remove(UpdateChecker.PREF_DOWNLOAD_ERROR).apply();
        UpdateDownloadReceiver.notifyReady(this, version, fileName);
        sendBroadcast(new Intent(UpdateDownloadReceiver.ACTION_UPDATE_READY).setPackage(getPackageName()));
        DiagnosticLog.write(this, "update_app_download_verified", version + " bytes=" + target.length());
    }

    private HttpURLConnection open(String initialUrl, long existing) throws Exception {
        String value = initialUrl;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL parsed = new URL(value);
            boolean isHttps = "https".equalsIgnoreCase(parsed.getProtocol());
            boolean isLocalHttp = "http".equalsIgnoreCase(parsed.getProtocol())
                    && UpdateChecker.isLocalOrPrivateHost(parsed.getHost());
            if (!isHttps && !isLocalHttp) throw new IllegalArgumentException("更新地址不是安全连接");
            HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*");
            connection.setRequestProperty("User-Agent", "zwm-gallery-android-update");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
            int response = connection.getResponseCode(); if (!isRedirect(response)) return connection;
            String location = connection.getHeaderField("Location"); connection.disconnect();
            if (location == null || location.trim().isEmpty()) throw new IllegalStateException("下载跳转地址为空");
            value = new URL(parsed, location).toString();
        }
        throw new IllegalStateException("下载跳转次数过多");
    }

    static boolean shouldAppend(long existing, int response, String contentRange) {
        return existing > 0 && response == 206 && contentRange != null
                && contentRange.toLowerCase(Locale.US).startsWith("bytes " + existing + "-");
    }

    static boolean isRedirect(int response) { return response == 301 || response == 302 || response == 303 || response == 307 || response == 308; }

    private void notifyProgress(String version, long downloaded, long total) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, progressNotification(version, downloaded, total));
    }

    private Notification progressNotification(String version, long downloaded, long total) {
        int percent = total > 0 ? (int) Math.min(100L, downloaded * 100L / total) : 0;
        String text = total > 0 ? percent + "% · " + formatBytes(downloaded) + " / " + formatBytes(total)
                : (downloaded > 0 ? "已下载 " + formatBytes(downloaded) : "正在连接下载服务…");
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载相册 " + version).setContentText(text)
                .setProgress(total > 0 ? 100 : 0, percent, total <= 0).setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void notifyFailure(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class); if (manager == null) return;
        PendingIntent openApp = PendingIntent.getActivity(this, 4403,
                getPackageManager().getLaunchIntentForPackage(getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(FAILURE_NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("相册更新未完成")
                .setContentText(text).setContentIntent(openApp).setAutoCancel(true).build());
    }

    private void recordFailure(String reason, boolean verification) {
        getSharedPreferences("device_share", MODE_PRIVATE).edit()
                .putString(UpdateChecker.PREF_DOWNLOAD_RESULT, verification ? UpdateChecker.RESULT_CHECKSUM_FAILED : UpdateChecker.RESULT_DOWNLOAD_FAILED)
                .putString(UpdateChecker.PREF_DOWNLOAD_ERROR, reason).apply();
        DiagnosticLog.write(this, verification ? "update_app_download_verify_failed" : "update_app_download_failed", reason);
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "更新下载进度", NotificationManager.IMPORTANCE_LOW));
    }

    private static void cleanupOldPackages(File root, File keep) {
        File[] files = root.listFiles(); if (files == null) return;
        for (File file : files) if (!file.equals(keep) && (file.getName().endsWith(".apk") || file.getName().endsWith(".apk.part"))) file.delete();
    }

    private static String formatBytes(long bytes) { return String.format(Locale.US, "%.1f MB", bytes / 1024d / 1024d); }
    private static String safe(String value) { if (value == null || value.trim().isEmpty()) return "网络连接中断"; String v = value.trim(); return v.length() > 160 ? v.substring(0, 160) : v; }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    private static final class VerificationFailure extends Exception { VerificationFailure(String message) { super(message); } }
}
