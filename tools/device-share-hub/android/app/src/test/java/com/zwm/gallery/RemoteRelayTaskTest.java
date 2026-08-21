package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RemoteRelayTaskTest {
    @Test public void parsesOnlyReadyTaskForTheCurrentDevice() throws Exception {
        JSONObject task = task("transfer_123456", "device_sender_1", "device_phone_1");
        RemoteRelayTask parsed = RemoteRelayTask.parse(task, "device_phone_1", 1_000L);

        assertEquals("transfer_123456", parsed.transferId);
        assertEquals(1, parsed.objectCount);
        assertEquals(12L, parsed.totalCipherBytes);
        assertFalse(parsed.expired(1_000L));
        assertTrue(parsed.expired(2_000L));
    }

    @Test public void rejectsWrongRecipientDuplicateIndexAndBadHash() throws Exception {
        assertRejected(task("transfer_123456", "device_sender_1", "device_other_1"),
                "device_phone_1");

        JSONObject duplicate = task("transfer_123456", "device_sender_1", "device_phone_1");
        duplicate.put("objects", new JSONArray()
                .put(object(0, 12L, "a".repeat(64)))
                .put(object(0, 12L, "b".repeat(64)));
        duplicate.put("totalCipherBytes", 24L);
        assertRejected(duplicate, "device_phone_1");

        JSONObject badHash = task("transfer_123456", "device_sender_1", "device_phone_1");
        badHash.getJSONArray("objects").getJSONObject(0).put("cipherSha256", "not-a-hash");
        assertRejected(badHash, "device_phone_1");
    }

    @Test public void ignoresUploadingTask() throws Exception {
        JSONObject task = task("transfer_123456", "device_sender_1", "device_phone_1");
        task.put("status", "uploading");
        assertRejected(task, "device_phone_1");
    }

    private static JSONObject task(String id, String sender, String recipient) throws Exception {
        return new JSONObject()
                .put("transferId", id)
                .put("senderDeviceId", sender)
                .put("recipientDeviceId", recipient)
                .put("status", "ready")
                .put("expiresAt", 2_000L)
                .put("totalCipherBytes", 12L)
                .put("objects", new JSONArray().put(object(0, 12L, "a".repeat(64))));
    }

    private static JSONObject object(int index, long bytes, String hash) throws Exception {
        return new JSONObject().put("index", index)
                .put("cipherBytes", bytes).put("cipherSha256", hash);
    }

    private static void assertRejected(JSONObject object, String recipient) throws Exception {
        try {
            RemoteRelayTask.parse(object, recipient, 1_000L);
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("task should be rejected");
    }
}
