package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Validated, metadata-only view of a relay inbox task.
 *
 * The task deliberately does not expose file names or paths.  Encrypted object
 * download/decryption will consume this boundary in a later phase.
 */
final class RemoteRelayTask {
    static final int MAX_OBJECTS = 1_000;

    final String transferId;
    final String senderDeviceId;
    final String recipientDeviceId;
    final String status;
    final int objectCount;
    final long totalCipherBytes;
    final long expiresAt;

    private RemoteRelayTask(String transferId, String senderDeviceId,
                            String recipientDeviceId, String status,
                            int objectCount, long totalCipherBytes, long expiresAt) {
        this.transferId = transferId;
        this.senderDeviceId = senderDeviceId;
        this.recipientDeviceId = recipientDeviceId;
        this.status = status;
        this.objectCount = objectCount;
        this.totalCipherBytes = totalCipherBytes;
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
        long expiresAt = object.optLong("expiresAt", 0L);
        if (expiresAt <= 0L) throw new IOException("远程任务有效期无效");

        JSONArray objects = object.optJSONArray("objects");
        if (objects == null || objects.length() < 1 || objects.length() > MAX_OBJECTS) {
            throw new IOException("远程任务对象数量无效");
        }
        Set<Integer> indexes = new HashSet<>();
        long totalCipherBytes = 0L;
        for (int i = 0; i < objects.length(); i++) {
            JSONObject item = objects.optJSONObject(i);
            if (item == null) throw new IOException("远程任务对象无效");
            int index = item.optInt("index", -1);
            long cipherBytes = item.optLong("cipherBytes", -1L);
            String cipherSha256 = item.optString("cipherSha256", "").trim();
            if (index < 0 || !indexes.add(index) || cipherBytes <= 0L
                    || !cipherSha256.matches("[0-9a-fA-F]{64}")) {
                throw new IOException("远程密文对象校验失败");
            }
            if (Long.MAX_VALUE - totalCipherBytes < cipherBytes) {
                throw new IOException("远程任务大小溢出");
            }
            totalCipherBytes += cipherBytes;
        }
        long declaredBytes = object.optLong("totalCipherBytes", totalCipherBytes);
        if (declaredBytes != totalCipherBytes) throw new IOException("远程任务总大小不一致");
        return new RemoteRelayTask(transferId, senderDeviceId, recipientDeviceId,
                status, objects.length(), totalCipherBytes, expiresAt);
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
}
