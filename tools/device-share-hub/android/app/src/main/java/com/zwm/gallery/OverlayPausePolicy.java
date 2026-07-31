package com.zwm.gallery;

final class OverlayPausePolicy {
    static final long ONE_MINUTE_MS = 60_000L;
    static final long THIRTY_MINUTES_MS = 30L * 60L * 1000L;
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
