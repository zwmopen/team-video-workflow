package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UpdateChecker {
    private static final long ONE_DAY_MS = 24L * 60L * 60L * 1000L;
    static final String PREF_PENDING_DOWNLOAD_ID = "pendingUpdateDownloadId";
    static final String PREF_PENDING_DOWNLOAD_SHA256 = "pendingUpdateDownloadSha256";
    static final String PREF_PENDING_DOWNLOAD_VERSION = "pendingUpdateDownloadVersion";
    static final String PREF_DOWNLOAD_RESULT = "updateDownloadResult";
    static final String RESULT_CHECKSUM_FAILED = "checksum_failed";

    private UpdateChecker() {
    }

    static void checkDaily(Activity activity) {
        long last = activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE)
                .getLong("lastUpdateCheck", 0);
        if (System.currentTimeMillis() - last < ONE_DAY_MS) return;
        check(activity, true);
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
                    if (isNewer(tag, current)) showUpdate(activity, tag, apkUrl, sha256);
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
                        ? "点击后由 Android 系统下载。下载完成时点系统通知，再确认安装即可；相册不再申请“安装其他应用”权限。"
                        : "新版本尚未准备好安装包，请稍后再试。")
                .setNegativeButton("稍后", null);
        if (downloadable) {
            builder.setPositiveButton("系统下载", (dialog, which) ->
                    downloadWithSystem(activity, version, apkUrl, sha256));
        }
        builder.show();
    }

    private static void downloadWithSystem(
            Activity activity, String version, String apkUrl, String expectedSha256) {
        try {
            Uri uri = Uri.parse(apkUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("更新地址不是安全连接");
            }
            DownloadManager manager =
                    (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("系统下载服务不可用");

            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle("相册 " + version + " 更新")
                    .setDescription("下载完成后，点系统通知安装")
                    .setMimeType("application/vnd.android.package-archive")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            long downloadId = manager.enqueue(request);
            boolean stored = activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE).edit()
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
            toast(activity, "已交给系统下载，完成后点通知安装");
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
