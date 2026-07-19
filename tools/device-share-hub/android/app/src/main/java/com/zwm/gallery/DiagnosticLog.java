package com.zwm.gallery;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

final class DiagnosticLog {
    private static final String TAG = "DeviceShareDiag";
    private static final String FILE_NAME = "diagnostics.log";
    private static final long MAX_BYTES = 512L * 1024L;

    private DiagnosticLog() {
    }

    static synchronized void write(Context context, String event, String detail) {
        String line = timestamp() + " | " + event + " | " + oneLine(detail) + "\n";
        try {
            File file = file(context);
            trimIfNeeded(file);
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            Log.w(TAG, "failed to write diagnostics", error);
        }
    }

    static synchronized String snapshot(Context context) {
        StringBuilder result = new StringBuilder();
        result.append("素材投送诊断信息\n");
        result.append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        result.append("Android：").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        result.append("状态：可用版本；请结合下方真机记录排查\n\n");
        result.append(tail(context, 120));
        return result.toString();
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String tail(Context context, int maxLines) {
        File file = file(context);
        if (!file.isFile()) return "暂无诊断记录";
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                while (lines.size() > maxLines) lines.removeFirst();
            }
        } catch (Exception error) {
            return "读取诊断记录失败：" + oneLine(error.getMessage());
        }
        return String.join("\n", lines);
    }

    private static void trimIfNeeded(File file) {
        if (!file.isFile() || file.length() <= MAX_BYTES) return;
        File old = new File(file.getParentFile(), FILE_NAME + ".old");
        if (old.exists() && !old.delete()) return;
        if (!file.renameTo(old)) return;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((timestamp() + " | log_rotated | previous log saved as diagnostics.log.old\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String oneLine(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
