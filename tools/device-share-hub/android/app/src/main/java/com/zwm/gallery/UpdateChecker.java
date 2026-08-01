package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;

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
    static final String PREF_DOWNLOAD_ERROR = "updateDownloadError";
    static final String RESULT_CHECKSUM_FAILED = "checksum_failed";
    static final String RESULT_DOWNLOAD_FAILED = "download_failed";
    static final String PREF_AUTO_UPDATE_ENABLED = "autoUpdateEnabled";

    private UpdateChecker() {
    }

    static void checkOnLaunch(Activity activity) {
        android.content.SharedPreferences prefs =
                activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE);
        String pendingVersion = prefs.getString(PREF_PENDING_DOWNLOAD_VERSION, "");
        if (!pendingVersion.isEmpty() && !isNewer(pendingVersion, currentVersion(activity))) {
            prefs.edit()
                    .remove(PREF_PENDING_DOWNLOAD_ID)
                    .remove(PREF_PENDING_DOWNLOAD_SHA256)
                    .remove(PREF_PENDING_DOWNLOAD_VERSION)
                    .apply();
        }
        String readyVersion = prefs.getString(PREF_READY_DOWNLOAD_VERSION, "");
        if (!readyVersion.isEmpty() && !isNewer(readyVersion, currentVersion(activity))) {
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
                        if (silent) downloadUpdate(activity, tag, apkUrl, sha256);
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
        if (!RESULT_CHECKSUM_FAILED.equals(result) && !RESULT_DOWNLOAD_FAILED.equals(result)) return;
        String detail = activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE)
                .getString(PREF_DOWNLOAD_ERROR, "");
        activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE).edit()
                .remove(PREF_DOWNLOAD_RESULT)
                .remove(PREF_DOWNLOAD_ERROR)
                .apply();
        boolean invalid = RESULT_CHECKSUM_FAILED.equals(result);
        new AlertDialog.Builder(activity)
                .setTitle(invalid ? "更新包校验失败" : "更新下载中断")
                .setMessage(invalid
                        ? "下载文件与发布版本不一致或系统无法解析，已自动删除。请重新下载。"
                        : "下载没有完成，已保留进度。请重新点击“下载更新”继续。"
                                + (detail.isEmpty() ? "" : "\n\n原因：" + detail))
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
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
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
        JSONObject value = new JSONObject(body.toString());
        if (!value.optString("apk_url", "").isEmpty()) return value;
        return normalizeGitHubRelease(value);
    }

    private static JSONObject normalizeGitHubRelease(JSONObject release) throws Exception {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IllegalStateException("发布版本没有安装包清单");
        String apkName = "";
        String apkUrl = "";
        String checksumsUrl = "";
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (name.matches("(?i)[A-Za-z0-9._-]+\\.apk")) {
                apkName = name;
                apkUrl = url;
            } else if ("SHA256SUMS.txt".equalsIgnoreCase(name)) {
                checksumsUrl = url;
            }
        }
        if (apkUrl.isEmpty() || checksumsUrl.isEmpty()) {
            throw new IllegalStateException("发布版本缺少 APK 或校验清单");
        }
        String checksum = checksumFor(fetchText(checksumsUrl, 256 * 1024), apkName);
        if (checksum.isEmpty()) throw new IllegalStateException("校验清单没有当前 APK");
        return new JSONObject()
                .put("tag_name", release.optString("tag_name", ""))
                .put("version_name", release.optString("tag_name", "").replaceFirst("^[vV]", ""))
                .put("apk_url", apkUrl)
                .put("sha256", checksum);
    }

    private static String fetchText(String url, int limit) throws Exception {
        HttpURLConnection connection = open(url);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "text/plain,*/*");
        connection.setRequestProperty("User-Agent", "zwm-gallery-android");
        int code = connection.getResponseCode();
        if (code != 200) throw new IllegalStateException("校验服务返回 " + code);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < limit) {
                body.append(line).append('\n');
            }
        } finally {
            connection.disconnect();
        }
        return body.toString();
    }

    static String checksumFor(String checksums, String fileName) {
        if (checksums == null || fileName == null) return "";
        for (String line : checksums.split("\\r?\\n")) {
            String trimmed = line.trim();
            int separator = trimmed.indexOf(' ');
            if (separator <= 0) continue;
            String hash = trimmed.substring(0, separator);
            String name = trimmed.substring(separator).trim().replaceFirst("^[*]", "");
            if (name.equals(fileName) && hash.matches("(?i)[0-9a-f]{64}")) {
                return hash.toLowerCase(java.util.Locale.US);
            }
        }
        return "";
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
                    downloadUpdate(activity, version, apkUrl, sha256));
        }
        builder.show();
    }

    private static void downloadUpdate(
            Activity activity, String version, String apkUrl, String expectedSha256) {
        try {
            android.content.SharedPreferences preferences =
                    activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE);
            Uri uri = Uri.parse(apkUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("更新地址不是安全连接");
            }
            String pendingVersion = preferences.getString(PREF_PENDING_DOWNLOAD_VERSION, "");
            String readyVersion = preferences.getString(PREF_READY_DOWNLOAD_VERSION, "");
            if (version.equals(readyVersion)
                    && showReadyInstallPrompt(activity)) {
                return;
            }
            if (!pendingVersion.isEmpty() && !version.equals(pendingVersion)) {
                AppUpdateService.deletePartial(activity, pendingVersion);
            }
            boolean stored = preferences.edit()
                    .putLong(PREF_PENDING_DOWNLOAD_ID, -1L)
                    .putString(PREF_PENDING_DOWNLOAD_SHA256,
                            expectedSha256 == null ? "" : expectedSha256.trim())
                    .putString(PREF_PENDING_DOWNLOAD_VERSION, version)
                    .remove(PREF_DOWNLOAD_RESULT)
                    .remove(PREF_DOWNLOAD_ERROR)
                    .commit();
            if (!stored) {
                throw new IllegalStateException("无法保存下载状态");
            }
            AppUpdateService.start(activity, version, apkUrl.trim(), expectedSha256);
            DiagnosticLog.write(activity, "update_app_download_started", version);
            toast(activity, "正在下载更新，可在通知中查看进度");
        } catch (Exception error) {
            DiagnosticLog.write(activity, "update_app_download_start_failed", error.getMessage());
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
