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
        return encode(senderId, senderId, java.util.UUID.randomUUID().toString(), 4, items);
    }

    static byte[] encode(String senderId, String originId, String messageId, int hopLimit,
                         List<SharedClipboardStore.Item> items) throws Exception {
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
                .put("originId", originId == null ? "" : originId)
                .put("messageId", messageId == null ? "" : messageId)
                .put("hopLimit", Math.max(0, Math.min(8, hopLimit)))
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
        String originId = root.optString("originId", senderId).trim();
        String messageId = root.optString("messageId", "").trim();
        int hopLimit = Math.max(0, Math.min(8, root.optInt("hopLimit", 0)));
        if (!originId.matches("[A-Za-z0-9._-]{6,160}")) originId = senderId;
        if (!messageId.matches("[A-Za-z0-9._-]{6,160}")) {
            messageId = senderId + "-" + Integer.toHexString(java.util.Arrays.hashCode(body));
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
        return new Decoded(senderId, originId, messageId, hopLimit, items);
    }

    static final class Decoded {
        final String senderId;
        final String originId;
        final String messageId;
        final int hopLimit;
        final List<SharedClipboardStore.Item> items;

        Decoded(String senderId, String originId, String messageId, int hopLimit,
                List<SharedClipboardStore.Item> items) {
            this.senderId = senderId;
            this.originId = originId;
            this.messageId = messageId;
            this.hopLimit = hopLimit;
            this.items = items;
        }
    }
}
