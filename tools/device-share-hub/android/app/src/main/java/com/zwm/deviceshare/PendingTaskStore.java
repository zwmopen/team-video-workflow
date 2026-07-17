package com.zwm.deviceshare;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class PendingTaskStore {
    private static final String FILE_NAME = "pending_task.json";

    private PendingTaskStore() {
    }

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static boolean exists(Context context) {
        return file(context).isFile();
    }

    static void write(Context context, JSONObject value) throws Exception {
        File target = file(context);
        File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        byte[] bytes = value.toString(2).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换待分享任务");
        if (!temp.renameTo(target)) throw new IllegalStateException("无法保存待分享任务");
    }

    static JSONObject read(Context context) throws Exception {
        try (FileInputStream input = new FileInputStream(file(context));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }
}
