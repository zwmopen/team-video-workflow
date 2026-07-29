package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class OperationLog {
    private static final String PREFS = "device_share";
    private static final String KEY = "operationRecords";
    private static final int MAX_RECORDS = 100;

    private OperationLog() {
    }

    static synchronized void add(Context context, String type, String message) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray old = new JSONArray(prefs.getString(KEY, "[]"));
            JSONArray next = new JSONArray();
            next.put(new JSONObject().put("time", System.currentTimeMillis())
                    .put("type", type).put("message", message));
            for (int index = 0; index < old.length() && next.length() < MAX_RECORDS; index++) {
                next.put(old.get(index));
            }
            prefs.edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized List<String> recent(Context context, int limit) {
        ArrayList<String> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]"));
            SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            for (int index = 0; index < values.length() && result.size() < limit; index++) {
                JSONObject item = values.getJSONObject(index);
                result.add(format.format(new Date(item.optLong("time")))
                        + "  " + item.optString("type") + "\n" + item.optString("message"));
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
