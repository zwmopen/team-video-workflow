package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OverlayPausePolicyTest {
    @Test
    public void computesTemporaryPauseDeadlines() {
        assertEquals(1_801_000L,
                OverlayPausePolicy.hiddenUntil(1_000L,
                        OverlayPausePolicy.THIRTY_MINUTES_MS));
        assertTrue(OverlayPausePolicy.isTemporarilyHidden(1_801_000L, 1_800_999L));
        assertFalse(OverlayPausePolicy.isTemporarilyHidden(1_801_000L, 1_801_000L));
    }

    @Test
    public void protectsDeadlineOverflowAndPermanentSentinel() {
        assertEquals(Long.MAX_VALUE,
                OverlayPausePolicy.hiddenUntil(Long.MAX_VALUE - 10,
                        OverlayPausePolicy.ONE_MINUTE_MS));
        assertEquals(0L, OverlayPausePolicy.hiddenUntil(1_000L, 0L));
    }
}
