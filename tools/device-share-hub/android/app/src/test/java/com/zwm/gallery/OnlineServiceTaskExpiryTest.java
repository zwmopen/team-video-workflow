package com.zwm.gallery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnlineServiceTaskExpiryTest {
    @Test
    public void incompleteIdleTaskExpires() {
        assertTrue(OnlineService.isIncomingTaskStale(
                300_000L, 0L, 0, 1));
    }

    @Test
    public void activeOrCompleteTaskDoesNotExpire() {
        assertFalse(OnlineService.isIncomingTaskStale(
                299_999L, 0L, 0, 1));
        assertFalse(OnlineService.isIncomingTaskStale(
                600_000L, 0L, 1, 1));
    }
}
