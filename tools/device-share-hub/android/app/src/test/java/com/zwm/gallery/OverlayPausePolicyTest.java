package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OverlayPausePolicyTest {
    @Test
    public void computesTemporaryPauseDeadlines() {
        assertEquals(31_000L,
                OverlayPausePolicy.hiddenUntil(1_000L,
                        OverlayPausePolicy.THIRTY_SECONDS_MS));
        assertTrue(OverlayPausePolicy.isTemporarilyHidden(31_000L, 30_999L));
        assertFalse(OverlayPausePolicy.isTemporarilyHidden(31_000L, 31_000L));
    }

    @Test
    public void protectsDeadlineOverflowAndPermanentSentinel() {
        assertEquals(Long.MAX_VALUE,
                OverlayPausePolicy.hiddenUntil(Long.MAX_VALUE - 10,
                        OverlayPausePolicy.THIRTY_SECONDS_MS));
        assertEquals(0L, OverlayPausePolicy.hiddenUntil(1_000L, 0L));
    }
}
