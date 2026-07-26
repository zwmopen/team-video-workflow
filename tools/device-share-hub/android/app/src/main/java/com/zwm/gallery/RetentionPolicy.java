package com.zwm.gallery;

import java.time.LocalDate;

final class RetentionPolicy {
    private static final long TRASH_DAYS = 7;

    private RetentionPolicy() {
    }

    static boolean shouldMoveToTrash(LocalDate sharedDate, LocalDate today) {
        return sharedDate != null && today.isAfter(sharedDate);
    }

    static boolean shouldPurge(LocalDate trashedDate, LocalDate today) {
        return trashedDate != null && !today.isBefore(trashedDate.plusDays(TRASH_DAYS));
    }

    static boolean shouldMoveToTrash(long firstSharedAtMs, long nowMs, long moveAfterMs) {
        return firstSharedAtMs > 0 && moveAfterMs >= 0 && nowMs >= firstSharedAtMs + moveAfterMs;
    }

    static boolean shouldPurge(long firstSharedAtMs, long nowMs, long deleteAfterMs) {
        return firstSharedAtMs > 0 && deleteAfterMs >= 0 && nowMs >= firstSharedAtMs + deleteAfterMs;
    }
}
