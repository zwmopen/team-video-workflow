package com.zwm.gallery;

final class OverlayPausePolicy {
    static final long THIRTY_SECONDS_MS = 30_000L;
    static final long FIVE_MINUTES_MS = 5L * 60L * 1000L;
    static final long ONE_DAY_MS = 24L * 60L * 60L * 1000L;

    private OverlayPausePolicy() {
    }

    static long hiddenUntil(long nowMs, long durationMs) {
        if (durationMs <= 0) return 0;
        if (nowMs > Long.MAX_VALUE - durationMs) return Long.MAX_VALUE;
        return nowMs + durationMs;
    }

    static boolean isTemporarilyHidden(long hiddenUntilMs, long nowMs) {
        return hiddenUntilMs > nowMs;
    }
}
