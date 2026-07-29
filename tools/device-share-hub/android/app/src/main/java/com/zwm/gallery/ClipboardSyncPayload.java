package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ClipboardSyncPayload {
    private static final int MAX_ITEMS = 500;

    private ClipboardSyncPayload() {
    }

    static byte[] encode(String senderId, List<SharedClipboardStore.Item> items) throws Exception {
        JSONArray records = new JSONArray();
        int count = 0;
        for (SharedClipboardStore.Item item : items) {
            if (count++ >= MAX_ITEMS) break;
            records.put(new JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind)
                    .put("text", item.text)
                    .put("updatedAt", item.updatedAt)
                    .put("deleted", item.deleted));
        }
        return new JSONObject()
                .put("senderId", senderId == null ? "" : senderId)
                .put("items", records)
                .toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static Decoded decode(byte[] body) throws Exception {
        JSONObject root = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        String senderId = root.optString("senderId", "").trim();
        if (!senderId.matches("[A-Za-z0-9._-]{6,160}")) {
            throw new IllegalArgumentException("发送设备身份无效");
        }
        JSONArray records = root.optJSONArray("items");
        if (records == null || records.length() > MAX_ITEMS) {
            throw new IllegalArgumentException("剪切板同步记录数量无效");
        }
        ArrayList<SharedClipboardStore.Item> items = new ArrayList<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject value = records.getJSONObject(index);
            items.add(new SharedClipboardStore.Item(
                    value.optString("id", ""),
                    value.optString("kind", ""),
                    value.optString("text", ""),
                    value.optLong("updatedAt", 0),
                    value.optBoolean("deleted", false)));
        }
        return new Decoded(senderId, items);
    }

    static final class Decoded {
        final String senderId;
        final List<SharedClipboardStore.Item> items;

        Decoded(String senderId, List<SharedClipboardStore.Item> items) {
            this.senderId = senderId;
            this.items = items;
        }
    }
}
