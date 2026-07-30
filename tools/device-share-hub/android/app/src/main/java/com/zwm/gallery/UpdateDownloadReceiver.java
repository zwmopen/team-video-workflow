package com.zwm.gallery;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateDownloadReceiver extends BroadcastReceiver {
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
        if (expected == null || expected.trim().isEmpty()) {
            DiagnosticLog.write(context, "update_system_download_complete",
                    version + " id=" + downloadId + " checksum=not_provided");
            markReady(preferences, downloadId, version);
            return;
        }

        try {
            Uri uri = manager.getUriForDownloadedFile(downloadId);
            if (uri == null) throw new IllegalStateException("系统没有返回下载文件");
            String actual;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("无法读取系统下载文件");
                actual = sha256(input);
            }
            if (!actual.equalsIgnoreCase(expected.trim())) {
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
            markReady(preferences, downloadId, version);
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
        preferences.edit()
                .putLong(UpdateChecker.PREF_READY_DOWNLOAD_ID, downloadId)
                .putString(UpdateChecker.PREF_READY_DOWNLOAD_VERSION,
                        version == null ? "" : version)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_ID)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_SHA256)
                .remove(UpdateChecker.PREF_PENDING_DOWNLOAD_VERSION)
                .apply();
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
