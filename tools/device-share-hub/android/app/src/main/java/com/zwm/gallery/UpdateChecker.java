package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateChecker {
    private static final long ONE_DAY_MS = 24L * 60L * 60L * 1000L;
    private static final String PREF_PENDING_APK = "pendingUpdateApk";

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

    static void resumePendingInstall(Activity activity) {
        String path = activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE)
                .getString(PREF_PENDING_APK, "");
        if (path == null || path.isEmpty()) return;
        File apk = new File(path);
        if (!apk.isFile()) {
            clearPending(activity);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls()) {
            launchInstaller(activity, apk);
        }
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
                        ? "点击后会在应用内直接下载，并显示真实进度。下载完成后只需确认系统安装。"
                        : "新版本尚未准备好安装包，请稍后再试。")
                .setNegativeButton("稍后", null);
        if (downloadable) {
            builder.setPositiveButton("下载并更新", (dialog, which) ->
                    downloadAndInstall(activity, version, apkUrl, sha256));
        }
        builder.show();
    }

    private static void downloadAndInstall(Activity activity, String version, String apkUrl, String expectedSha256) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        DownloadDialog progress = new DownloadDialog(activity, cancelled);
        progress.show();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            File temporary = null;
            try {
                File directory = new File(activity.getFilesDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("无法创建更新目录");
                }
                temporary = new File(directory, "album-update.tmp");
                File target = new File(directory, "album-Android-v" + safeVersion(version) + ".apk");
                download(apkUrl, temporary, progress, cancelled);
                if (cancelled.get()) throw new DownloadCancelled();
                if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                    String actual = sha256(temporary);
                    if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
                        throw new SecurityException("安装包校验失败，请稍后重试");
                    }
                }
                if (target.exists() && !target.delete()) throw new IllegalStateException("旧安装包无法替换");
                if (!temporary.renameTo(target)) throw new IllegalStateException("安装包保存失败");
                activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE).edit()
                        .putString(PREF_PENDING_APK, target.getAbsolutePath()).apply();
                DiagnosticLog.write(activity, "update_download_complete",
                        version + " bytes=" + target.length());
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    installOrRequestPermission(activity, target);
                });
            } catch (DownloadCancelled ignored) {
                if (temporary != null) temporary.delete();
                DiagnosticLog.write(activity, "update_download_cancelled", version);
                activity.runOnUiThread(progress::dismiss);
            } catch (Exception error) {
                if (temporary != null) temporary.delete();
                DiagnosticLog.write(activity, "update_download_failed", error.getMessage());
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("更新没有下载完成")
                            .setMessage(error.getMessage() == null ? "请确认网络后重试" : error.getMessage())
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } finally {
                executor.shutdown();
            }
        });
    }

    private static void download(String value, File target, DownloadDialog progress,
                                 AtomicBoolean cancelled) throws Exception {
        HttpURLConnection connection = open(value);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "zwm-gallery-android");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("下载服务返回 " + code);
        long total = connection.getContentLengthLong();
        long received = 0;
        long lastUiUpdate = 0;
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (cancelled.get()) throw new DownloadCancelled();
                output.write(buffer, 0, count);
                received += count;
                long now = System.currentTimeMillis();
                if (now - lastUiUpdate >= 120 || (total > 0 && received == total)) {
                    progress.update(received, total);
                    lastUiUpdate = now;
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        if (received <= 0) throw new IllegalStateException("下载内容为空");
    }

    private static HttpURLConnection open(String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static void installOrRequestPermission(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("安装包已下载")
                    .setMessage("Android 需要你允许“相册”安装这一次更新。打开权限后会自动继续。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("去允许", (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception error) {
                            DiagnosticLog.write(activity, "update_permission_failed", error.getMessage());
                            toast(activity, "无法打开安装权限设置");
                        }
                    })
                    .show();
            return;
        }
        launchInstaller(activity, apk);
    }

    private static void launchInstaller(Activity activity, File apk) {
        try {
            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(activity.getPackageName() + ".files")
                    .appendPath("updates")
                    .appendPath(apk.getName())
                    .build();
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PackageManager manager = activity.getPackageManager();
            if (install.resolveActivity(manager) == null) {
                throw new IllegalStateException("系统没有可用的安装程序");
            }
            DiagnosticLog.write(activity, "update_installer_opened", apk.getName());
            clearPending(activity);
            activity.startActivity(install);
        } catch (Exception error) {
            DiagnosticLog.write(activity, "update_install_failed", error.getMessage());
            new AlertDialog.Builder(activity)
                    .setTitle("无法打开系统安装")
                    .setMessage(error.getMessage() == null ? "请稍后重试" : error.getMessage())
                    .setPositiveButton("知道了", null)
                    .show();
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

    private static int number(String value) {
        try { return Integer.parseInt(value.replaceAll("[^0-9].*$", "")); }
        catch (Exception ignored) { return 0; }
    }

    private static String safeVersion(String version) {
        String value = version == null ? "update" : version.replaceAll("[^0-9A-Za-z._-]", "");
        return value.isEmpty() ? "update" : value;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format(Locale.US, "%02x", item));
        return value.toString();
    }

    private static void markChecked(Activity activity) {
        activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE).edit()
                .putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
    }

    private static void clearPending(Activity activity) {
        activity.getSharedPreferences("device_share", Activity.MODE_PRIVATE).edit()
                .remove(PREF_PENDING_APK).apply();
    }

    private static void toast(Activity activity, String value) {
        Toast.makeText(activity, value, Toast.LENGTH_SHORT).show();
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class DownloadCancelled extends Exception {
    }

    private static final class DownloadDialog {
        private final Activity activity;
        private final AtomicBoolean cancelled;
        private final ProgressBar bar;
        private final TextView detail;
        private final AlertDialog dialog;

        DownloadDialog(Activity activity, AtomicBoolean cancelled) {
            this.activity = activity;
            this.cancelled = cancelled;
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0);
            detail = new TextView(activity);
            detail.setText("正在准备下载…");
            detail.setTextSize(15);
            detail.setTextColor(Color.DKGRAY);
            detail.setGravity(Gravity.CENTER_HORIZONTAL);
            bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            content.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(activity, 8));
            barParams.topMargin = dp(activity, 16);
            content.addView(bar, barParams);
            dialog = new AlertDialog.Builder(activity)
                    .setTitle("正在下载更新")
                    .setView(content)
                    .setNegativeButton("取消", (value, which) -> cancelled.set(true))
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(value -> cancelled.set(true));
        }

        void show() { dialog.show(); }
        void dismiss() { if (dialog.isShowing()) dialog.dismiss(); }

        void update(long received, long total) {
            activity.runOnUiThread(() -> {
                if (total > 0) {
                    int percent = (int) Math.min(100, received * 100 / total);
                    bar.setIndeterminate(false);
                    bar.setMax(100);
                    bar.setProgress(percent);
                    detail.setText(percent + "%  ·  " + size(received) + " / " + size(total));
                } else {
                    detail.setText("已下载 " + size(received));
                }
            });
        }

        private static String size(long bytes) {
            if (bytes >= 1024L * 1024L) return String.format(Locale.CHINA, "%.1f MB", bytes / 1024d / 1024d);
            if (bytes >= 1024L) return String.format(Locale.CHINA, "%.0f KB", bytes / 1024d);
            return bytes + " B";
        }
    }
}
