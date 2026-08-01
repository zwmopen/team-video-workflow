package com.zwm.gallery;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateDownloadReceiver extends BroadcastReceiver {
    static final String ACTION_UPDATE_READY = "com.zwm.gallery.UPDATE_READY";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        SharedPreferences preferences =
                context.getSharedPreferences("device_share", Context.MODE_PRIVATE);
        if (downloadId < 0
                || downloadId != preferences.getLong(
                        UpdateChecker.PREF_PENDING_DOWNLOAD_ID, -1L)) return;

        PendingResult pending = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                verifyCompletedDownload(context, preferences, downloadId);
            } finally {
                pending.finish();
                executor.shutdown();
            }
        });
    }

    private static void verifyCompletedDownload(
            Context context, SharedPreferences preferences, long downloadId) {
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return;

        try (Cursor cursor = manager.query(
                new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                int reason = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                DiagnosticLog.write(context, "update_system_download_failed",
                        "id=" + downloadId + " reason=" + reason);
                clearPending(preferences);
                return;
            }
        } catch (Exception error) {
            DiagnosticLog.write(context, "update_system_download_query_failed", error.getMessage());
            return;
        }

        String expected = preferences.getString(
                UpdateChecker.PREF_PENDING_DOWNLOAD_SHA256, "");
        String version = preferences.getString(
                UpdateChecker.PREF_PENDING_DOWNLOAD_VERSION, "");
        try {
            Uri uri = manager.getUriForDownloadedFile(downloadId);
            if (uri == null) throw new IllegalStateException("系统没有返回下载文件");
            String actual;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("无法读取系统下载文件");
                actual = sha256(input);
            }
            if (expected != null && !expected.trim().isEmpty()
                    && !actual.equalsIgnoreCase(expected.trim())) {
                manager.remove(downloadId);
                preferences.edit()
                        .putString(UpdateChecker.PREF_DOWNLOAD_RESULT,
                                UpdateChecker.RESULT_CHECKSUM_FAILED)
                        .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_ID)
                        .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_SHA256)
                        .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_VERSION)
                        .apply();
                DiagnosticLog.write(context, "update_system_download_checksum_failed",
                        version + " id=" + downloadId);
                return;
            }
            DiagnosticLog.write(context, "update_system_download_verified",
                    version + " id=" + downloadId);
            File root = new File(context.getFilesDir(), "updates");
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IllegalStateException("无法创建更新缓存目录");
            }
            String fileName = UpdateChecker.updateFileName(version);
            File local = new File(root, fileName);
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(local, false)) {
                if (input == null) throw new IllegalStateException("无法读取系统下载文件");
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
            }
            UpdatePackageValidator.validate(context, local, version);
            markReady(preferences, downloadId, version, fileName);
            notifyReady(context, version, fileName);
            context.sendBroadcast(new Intent(ACTION_UPDATE_READY)
                    .setPackage(context.getPackageName()));
        } catch (Exception error) {
            manager.remove(downloadId);
            preferences.edit()
                    .putString(UpdateChecker.PREF_DOWNLOAD_RESULT,
                            UpdateChecker.RESULT_CHECKSUM_FAILED)
                    .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_ID)
                    .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_SHA256)
                    .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_VERSION)
                    .apply();
            DiagnosticLog.write(context, "update_system_download_verify_failed", error.getMessage());
        }
    }

    private static void clearPending(SharedPreferences preferences) {
        preferences.edit()
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_ID)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_SHA256)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_VERSION)
                .apply();
    }

    private static void markReady(
            SharedPreferences preferences, long downloadId, String version) {
        markReady(preferences, downloadId, version, "");
    }

    private static void markReady(
            SharedPreferences preferences, long downloadId, String version, String fileName) {
        preferences.edit()
                .putLong(UpdateChecker.PREF_READY_DOWNLOAD_ID, downloadId)
                .putString(UpdateChecker.PREF_READY_DOWNLOAD_VERSION,
                        version == null ? "" : version)
                .putString(UpdateChecker.PREF_READY_FILE_NAME,
                        fileName == null ? "" : fileName)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_ID)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_SHA256)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_VERSION)
                .apply();
    }

    private static void notifyReady(Context context, String version, String fileName) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        String channelId = "device_share_updates_v2";
        manager.createNotificationChannel(new NotificationChannel(
                channelId, "软件更新", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent install = PendingIntent.getActivity(
                context, 4402, new Intent(context, UpdateInstallActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("相册 " + version + " 已下载并验证")
                .setContentText(fileName + " · 点此安装")
                .setContentIntent(install)
                .setAutoCancel(false)
                .build();
        manager.notify(4402, notification);
    }

    static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format(Locale.US, "%02x", item));
        return value.toString();
    }
}
