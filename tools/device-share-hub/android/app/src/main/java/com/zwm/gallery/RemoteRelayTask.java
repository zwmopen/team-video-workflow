package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Validated view of a relay inbox task. Plain mode carries public file metadata. */
final class RemoteRelayTask {
    static final int MAX_OBJECTS = 1_000;

    final String transferId;
    final String senderDeviceId;
    final String recipientDeviceId;
    final String status;
    final String mode;
    final int objectCount;
    final long totalBytes;
    final java.util.List<ObjectInfo> objects;
    /** Legacy name retained for callers and diagnostics during the protocol migration. */
    final long totalCipherBytes;
    final long expiresAt;

    private RemoteRelayTask(String transferId, String senderDeviceId,
                            String recipientDeviceId, String status,
                            String mode, java.util.List<ObjectInfo> objects,
                            long totalBytes, long expiresAt) {
        this.transferId = transferId;
        this.senderDeviceId = senderDeviceId;
        this.recipientDeviceId = recipientDeviceId;
        this.status = status;
        this.mode = mode;
        this.objects = java.util.Collections.unmodifiableList(objects);
        this.objectCount = objects.size();
        this.totalBytes = totalBytes;
        this.totalCipherBytes = totalBytes;
        this.expiresAt = expiresAt;
    }

    static RemoteRelayTask parse(JSONObject object, String expectedRecipientId,
                                 long nowMs) throws IOException {
        if (object == null) throw new IOException("远程任务为空");
        String transferId = safeId(object.optString("transferId", ""), "远程任务标识");
        String senderDeviceId = safeId(object.optString("senderDeviceId", ""), "发送设备标识");
        String recipientDeviceId = safeId(object.optString("recipientDeviceId", ""), "接收设备标识");
        if (expectedRecipientId == null || !expectedRecipientId.equals(recipientDeviceId)) {
            throw new IOException("远程任务接收设备不匹配");
        }
        String status = object.optString("status", "").trim();
        if (!"ready".equals(status)) throw new IOException("远程任务尚未提交");
        String mode = object.optString("mode", "encrypted").trim();
        if (!"plain".equals(mode) && !"encrypted".equals(mode)) {
            throw new IOException("远程任务传输模式无效");
        }
        long expiresAt = object.optLong("expiresAt", 0L);
        if (expiresAt <= 0L) throw new IOException("远程任务有效期无效");

        JSONArray objects = object.optJSONArray("objects");
        if (objects == null || objects.length() < 1 || objects.length() > MAX_OBJECTS) {
            throw new IOException("远程任务对象数量无效");
        }
        Set<Integer> indexes = new HashSet<>();
        java.util.ArrayList<ObjectInfo> parsedObjects = new java.util.ArrayList<>();
        long totalBytes = 0L;
        for (int i = 0; i < objects.length(); i++) {
            JSONObject item = objects.optJSONObject(i);
            if (item == null) throw new IOException("远程任务对象无效");
            int index = item.optInt("index", -1);
            long bytes = "plain".equals(mode)
                    ? item.optLong("bytes", item.optLong("objectBytes", -1L))
                    : item.optLong("cipherBytes", -1L);
            String sha256 = "plain".equals(mode)
                    ? item.optString("sha256", item.optString("objectSha256", "")).trim()
                    : item.optString("cipherSha256", "").trim();
            String name = item.optString("name", "").trim();
            String mime = item.optString("mime", "application/octet-stream").trim();
            if (index < 0 || !indexes.add(index) || bytes <= 0L
                    || !sha256.matches("[0-9a-fA-F]{64}")
                    || ("plain".equals(mode) && !safeName(name))) {
                throw new IOException("远程对象校验失败");
            }
            if (Long.MAX_VALUE - totalBytes < bytes) {
                throw new IOException("远程任务大小溢出");
            }
            totalBytes += bytes;
            parsedObjects.add(new ObjectInfo(index, bytes, sha256.toLowerCase(),
                    "plain".equals(mode) ? name : "", mime));
        }
        long declaredBytes = "plain".equals(mode)
                ? object.optLong("totalBytes", totalBytes)
                : object.optLong("totalCipherBytes", totalBytes);
        if (declaredBytes != totalBytes) throw new IOException("远程任务总大小不一致");
        return new RemoteRelayTask(transferId, senderDeviceId, recipientDeviceId,
                status, mode, parsedObjects, totalBytes, expiresAt);
    }

    boolean expired(long nowMs) {
        return expiresAt <= nowMs;
    }

    private static String safeId(String value, String label) throws IOException {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IOException(label + "无效");
        }
        return normalized;
    }

    private static boolean safeName(String value) {
        return value != null && !value.isEmpty() && value.length() <= 240
                && !".".equals(value) && !"..".equals(value)
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0
                && value.indexOf('\0') < 0;
    }

    static final class ObjectInfo {
        final int index;
        final long bytes;
        final String sha256;
        final String name;
        final String mime;

        ObjectInfo(int index, long bytes, String sha256, String name, String mime) {
            this.index = index;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.name = name;
            this.mime = mime;
        }
    }
}
