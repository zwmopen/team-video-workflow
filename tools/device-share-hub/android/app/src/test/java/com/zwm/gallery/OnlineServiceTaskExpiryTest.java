package com.zwm.gallery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnlineServiceTaskExpiryTest {
    @Test
    public void incompleteIdleTaskExpires() {
        assertTrue(OnlineService.isIncomingTaskStale(
                1_800_000L, 0L, 0, 1));
    }

    @Test
    public void activeOrCompleteTaskDoesNotExpire() {
        assertFalse(OnlineService.isIncomingTaskStale(
                1_799_999L, 0L, 0, 1));
        assertFalse(OnlineService.isIncomingTaskStale(
                3_600_000L, 0L, 1, 1));
    }

    @Test
    public void resumableRangeMustMatchTheRemainingRequestBody() {
        assertTrue(OnlineService.isValidResumeRange(0L, 100L, 100L));
        assertTrue(OnlineService.isValidResumeRange(40L, 100L, 60L));
        assertFalse(OnlineService.isValidResumeRange(41L, 100L, 60L));
        assertFalse(OnlineService.isValidResumeRange(101L, 100L, 0L));
    }

    @Test
    public void relayRetryAfterP2PImportOnlyRepairsAck() {
        assertTrue(OnlineService.shouldImportRemoteTask(false));
        assertFalse(OnlineService.shouldImportRemoteTask(true));
    }
}
