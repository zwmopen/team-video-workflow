package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
                HttpURLConnection connection = (HttpURLConnection) new URL(UpdateEndpoint.RELEASE_API).openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "zwm-gallery-android");
                int code = connection.getResponseCode();
                if (code == 404) {
                    markChecked(activity);
                    if (!silent) activity.runOnUiThread(() -> toast(activity, "当前还没有公开发布的新版本"));
                    return;
                }
                if (code != 200) throw new IllegalStateException("服务器返回 " + code);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && body.length() < 1024 * 1024) body.append(line);
                }
                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                String url = release.optString("html_url", "https://github.com/zwmopen/gallery-updates/releases");
                String current = currentVersion(activity);
                markChecked(activity);
                activity.runOnUiThread(() -> {
                    if (isNewer(tag, current)) showUpdate(activity, tag, url);
                    else if (!silent) toast(activity, "当前已是最新版本 " + current);
                });
            } catch (Exception error) {
                DiagnosticLog.write(activity, "update_check_failed", error.getMessage());
                if (!silent) activity.runOnUiThread(() -> toast(activity, "检查失败，请稍后再试"));
            } finally {
                executor.shutdown();
            }
        });
    }

    static String currentVersion(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "0.0.0";
        }
    }

    private static void showUpdate(Activity activity, String version, String url) {
        new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + version)
                .setMessage("下载安装仍由你确认，不会静默安装。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("查看更新", (dialog, which) ->
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                .show();
    }

    private static boolean isNewer(String candidate, String current) {
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
