package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RelayTaskMetadataTest {
    @Test
    public void forwardingDecrementsHopAndKeepsExpiry() {
        long expiry = System.currentTimeMillis() + 30_000L;
        RelayTaskMetadata metadata = new RelayTaskMetadata(
                "message-123", "android-origin", "windows-main",
                "android-origin", "screenshot", expiry, 4);
        RelayTaskMetadata forwarded = metadata.forwardedBy("android-relay");
        assertEquals(3, forwarded.hopLimit);
        assertEquals(expiry, forwarded.expiresAt);
        assertEquals("android-relay", forwarded.previousHopId);
        assertEquals("windows-main", forwarded.destinationId);
    }

    @Test
    public void lifetimeAndHopCountAreBounded() {
        RelayTaskMetadata metadata = new RelayTaskMetadata(
                "message-456", "android-origin", "windows-main",
                "android-origin", "file", Long.MAX_VALUE, 99);
        assertEquals(8, metadata.hopLimit);
        assertTrue(metadata.expiresAt <= System.currentTimeMillis()
                + RelayTaskMetadata.MAX_LIFETIME_MS);
    }
}
