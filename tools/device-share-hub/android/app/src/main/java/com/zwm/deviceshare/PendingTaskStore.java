package com.zwm.deviceshare;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        Files.writeString(temp.toPath(), value.toString(2), StandardCharsets.UTF_8);
        if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换待分享任务");
        if (!temp.renameTo(target)) throw new IllegalStateException("无法保存待分享任务");
    }

    static JSONObject read(Context context) throws Exception {
        return new JSONObject(Files.readString(file(context).toPath(), StandardCharsets.UTF_8));
    }
}
