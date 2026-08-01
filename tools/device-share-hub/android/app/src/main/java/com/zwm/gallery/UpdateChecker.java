package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UpdateChecker {
    static final String PREF_PENDING_DOWNLOAD_ID = "pendingUpdateDownloadId";
    static final String PREF_PENDING_DOWNLOAD_SHA256 = "pendingUpdateDownloadSha256";
    static final String PREF_PENDING_DOWNLOAD_VERSION = "pendingUpdateDownloadVersion";
    static final String PREF_READY_DOWNLOAD_ID = "readyUpdateDownloadId";
    static final String PREF_READY_DOWNLOAD_VERSION = "readyUpdateDownloadVersion";
    static final String PREF_READY_FILE_NAME = "readyUpdateFileName";
    static final String PREF_DOWNLOAD_RESULT = "updateDownloadResult";
    static final String RESULT_CHECKSUM_FAILED = "checksum_failed";
    static final String PREF_AUTO_UPDATE_ENABLED = "autoUpdateEnabled";

    private UpdateChecker() {
    }

    static void checkOnLaunch(Activity activity) {
        android.content.SharedPreferences prefs =
                activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE);
        String pendingVersion = prefs.getString(PREF_PENDING_DOWNLOAD_VERSION, "");
        if (!pendingVersion.isEmpty() && !isNewer(pendingVersion, currentVersion(activity))) {
            removeTrackedDownload(activity, prefs.getLong(PREF_PENDING_DOWNLOAD_ID, -1L));
            prefs.edit()
                    .remove(PREF_PENDING_DOWNLOAD_ID)
                    .remove(PREF_PENDING_DOWNLOAD_SHA256)
                    .remove(PREF_PENDING_DOWNLOAD_VERSION)
                    .apply();
        }
        String readyVersion = prefs.getString(PREF_READY_DOWNLOAD_VERSION, "");
        if (!readyVersion.isEmpty() && !isNewer(readyVersion, currentVersion(activity))) {
            removeTrackedDownload(activity, prefs.getLong(PREF_READY_DOWNLOAD_ID, -1L));
            removeReadyFile(activity, prefs.getString(PREF_READY_FILE_NAME, ""));
            prefs.edit()
                    .remove(PREF_READY_DOWNLOAD_ID)
                    .remove(PREF_READY_DOWNLOAD_VERSION)
                    .remove(PREF_READY_FILE_NAME)
                    .apply();
        }
        if (!prefs.getBoolean(PREF_AUTO_UPDATE_ENABLED, true)) return;
        if (!readyVersion.isEmpty() && isNewer(readyVersion, currentVersion(activity))) return;
        check(activity, false);
    }

    static void check(Activity activity, boolean silent) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                JSONObject release = fetchManifest();
                String tag = release.optString("version_name",
                        release.optString("tag_name", "")).replaceFirst("^[vV]", "");
                String apkUrl = release.optString("apk_url", "");
                String sha256 = release.optString("sha256", "");
                String current = currentVersion(activity);
                markChecked(activity);
                activity.runOnUiThread(() -> {
                    if (isNewer(tag, current)) {
                        if (silent) downloadWithSystem(activity, tag, apkUrl, sha256);
                        else showUpdate(activity, tag, apkUrl, sha256);
                    }
                    else if (!silent) toast(activity, "当前已经是最新版本 " + current);
                });
            } catch (Exception error) {
                DiagnosticLog.write(activity, "update_check_failed", error.getMessage());
                if (!silent) activity.runOnUiThread(() ->
                        toast(activity, "检查失败，请确认网络后重试"));
            } finally {
                executor.shutdown();
            }
        });
    }

    static void reportDownloadProblem(Activity activity) {
        String result = activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE)
                .getString(PREF_DOWNLOAD_RESULT, "");
        if (!RESULT_CHECKSUM_FAILED.equals(result)) return;
        activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE).edit()
                .remove(PREF_DOWNLOAD_RESULT).apply();
        new AlertDialog.Builder(activity)
                .setTitle("更新包校验失败")
                .setMessage("系统下载的文件与发布版本不一致，已自动删除。请重新检查更新。")
                .setPositiveButton("知道了", null)
                .show();
    }

    static boolean showReadyInstallPrompt(Activity activity) {
        android.content.SharedPreferences preferences =
                activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE);
        String version = preferences.getString(PREF_READY_DOWNLOAD_VERSION, "");
        String fileName = preferences.getString(PREF_READY_FILE_NAME, "");
        if (!isNewer(version, currentVersion(activity))
                || fileName == null
                || !fileName.matches("[A-Za-z0-9._-]+\\.apk")) return false;
        File file = new File(new File(activity.getFilesDir(), "updates"), fileName);
        if (!file.isFile()) return false;
        String size = String.format(java.util.Locale.US, "%.2f MB",
                file.length() / 1024d / 1024d);
        new AlertDialog.Builder(activity)
                .setTitle("更新 " + version + " 已准备好")
                .setMessage(fileName + "\n" + size
                        + "\n\n安装包已校验。点击“安装”进入 Android 系统安装界面。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("安装", (dialog, which) ->
                        activity.startActivity(new Intent(activity, UpdateInstallActivity.class)))
                .show();
        return true;
    }

    static String currentVersion(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "0.0.0";
        }
    }

    private static JSONObject fetchManifest() throws Exception {
        HttpURLConnection connection = open(UpdateEndpoint.RELEASE_API);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "zwm-gallery-android");
        int code = connection.getResponseCode();
        if (code == 404) throw new IllegalStateException("还没有公开发布版本");
        if (code != 200) throw new IllegalStateException("更新服务返回 " + code);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 1024 * 1024) body.append(line);
        } finally {
            connection.disconnect();
        }
        return new JSONObject(body.toString());
    }

    private static void showUpdate(Activity activity, String version, String apkUrl, String sha256) {
        boolean downloadable = apkUrl != null && !apkUrl.trim().isEmpty();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + version)
                .setMessage(downloadable
                        ? "是否下载更新？下载完成后会先校验安装包，再由你点击“安装”进入 Android 系统安装界面。"
                        : "新版本尚未准备好安装包，请稍后再试。")
                .setNegativeButton("稍后", null);
        if (downloadable) {
            builder.setPositiveButton("下载更新", (dialog, which) ->
                    downloadWithSystem(activity, version, apkUrl, sha256));
        }
        builder.show();
    }

    private static void downloadWithSystem(
            Activity activity, String version, String apkUrl, String expectedSha256) {
        try {
            android.content.SharedPreferences preferences =
                    activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE);
            Uri uri = Uri.parse(apkUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("更新地址不是安全连接");
            }
            DownloadManager manager =
                    (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("系统下载服务不可用");
            long pendingId = preferences.getLong(PREF_PENDING_DOWNLOAD_ID, -1L);
            String pendingVersion = preferences.getString(PREF_PENDING_DOWNLOAD_VERSION, "");
            int pendingStatus = trackedDownloadStatus(manager, pendingId);
            if (pendingId >= 0 && version.equals(pendingVersion)
                    && isDownloadStateUsable(pendingStatus)) {
                toast(activity, downloadStatusMessage(pendingStatus));
                return;
            }
            long readyId = preferences.getLong(PREF_READY_DOWNLOAD_ID, -1L);
            String readyVersion = preferences.getString(PREF_READY_DOWNLOAD_VERSION, "");
            int readyStatus = trackedDownloadStatus(manager, readyId);
            if (readyId >= 0 && version.equals(readyVersion)
                    && isDownloadStateUsable(readyStatus)) {
                toast(activity, downloadStatusMessage(readyStatus));
                return;
            }

            if (pendingId >= 0) manager.remove(pendingId);
            if (readyId >= 0 && readyId != pendingId) manager.remove(readyId);
            removeReadyFile(activity, preferences.getString(PREF_READY_FILE_NAME, ""));
            preferences.edit()
                    .remove(PREF_PENDING_DOWNLOAD_ID)
                    .remove(PREF_PENDING_DOWNLOAD_SHA256)
                    .remove(PREF_PENDING_DOWNLOAD_VERSION)
                    .remove(PREF_READY_DOWNLOAD_ID)
                    .remove(PREF_READY_DOWNLOAD_VERSION)
                    .remove(PREF_READY_FILE_NAME)
                    .commit();

            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle("相册 " + version + " 更新")
                    .setDescription("下载完成并校验后，由你确认安装")
                    .setMimeType("application/vnd.android.package-archive")
                    .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, updateFileName(version))
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            long downloadId = manager.enqueue(request);
            boolean stored = preferences.edit()
                    .putLong(PREF_PENDING_DOWNLOAD_ID, downloadId)
                    .putString(PREF_PENDING_DOWNLOAD_SHA256,
                            expectedSha256 == null ? "" : expectedSha256.trim())
                    .putString(PREF_PENDING_DOWNLOAD_VERSION, version)
                    .remove(PREF_DOWNLOAD_RESULT)
                    .commit();
            if (!stored) {
                manager.remove(downloadId);
                throw new IllegalStateException("无法保存下载状态");
            }
            DiagnosticLog.write(activity, "update_system_download_started",
                    version + " id=" + downloadId);
            toast(activity, "正在下载；校验完成后会提示安装");
        } catch (Exception error) {
            DiagnosticLog.write(activity, "update_system_download_failed", error.getMessage());
            new AlertDialog.Builder(activity)
                    .setTitle("无法开始下载")
                    .setMessage(error.getMessage() == null ? "请确认网络后重试" : error.getMessage())
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private static HttpURLConnection open(String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static int trackedDownloadStatus(DownloadManager manager, long downloadId) {
        if (downloadId < 0) return -1;
        try (Cursor cursor = manager.query(
                new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) return -1;
            return cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        } catch (Exception error) {
            return -1;
        }
    }

    static boolean isDownloadStateUsable(int status) {
        return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED
                || status == DownloadManager.STATUS_SUCCESSFUL;
    }

    static String downloadStatusMessage(int status) {
        return status == DownloadManager.STATUS_SUCCESSFUL
                ? "安装包已经验证，请点击安装"
                : "更新包正在系统下载，请查看通知进度";
    }

    private static void removeTrackedDownload(Activity activity, long downloadId) {
        if (downloadId < 0) return;
        DownloadManager manager =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) manager.remove(downloadId);
    }

    private static void removeReadyFile(Activity activity, String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]+\\.apk")) return;
        File file = new File(new File(activity.getFilesDir(), "updates"), fileName);
        if (file.isFile() && !file.delete()) {
            DiagnosticLog.write(activity, "update_old_file_delete_failed", fileName);
        }
    }

    static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isEmpty()) return false;
        String[] left = candidate.split("\\.");
        String[] right = current.split("\\.");
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int a = index < left.length ? number(left[index]) : 0;
            int b = index < right.length ? number(right[index]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    static String updateFileName(String version) {
        String safeVersion = version == null ? "" :
                version.trim().replaceAll("[^0-9A-Za-z._-]", "_");
        if (safeVersion.isEmpty()) safeVersion = "latest";
        return "album-Android-" + safeVersion + ".apk";
    }

    private static int number(String value) {
        try { return Integer.parseInt(value.replaceAll("[^0-9].*$", "")); }
        catch (Exception ignored) { return 0; }
    }

    private static void markChecked(Activity activity) {
        activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE).edit()
                .putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
    }

    private static void toast(Activity activity, String value) {
        Toast.makeText(activity, value, Toast.LENGTH_SHORT).show();
    }
}
