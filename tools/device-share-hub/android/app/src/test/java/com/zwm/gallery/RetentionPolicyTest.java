package com.zwm.gallery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RetentionPolicyTest {
    @Test
    public void oneHourBoundaryIsExact() {
        long shared = 10_000L;
        long hour = 3_600_000L;
        assertFalse(RetentionPolicy.shouldMoveToTrash(shared, shared + hour - 1, hour));
        assertTrue(RetentionPolicy.shouldMoveToTrash(shared, shared + hour, hour));
        assertFalse(RetentionPolicy.shouldPurge(shared, shared + hour - 1, hour));
        assertTrue(RetentionPolicy.shouldPurge(shared, shared + hour, hour));
    }

    @Test
    public void oldRecordsWithoutTimestampAreNeverRetroactivelyPurged() {
        assertFalse(RetentionPolicy.shouldMoveToTrash(0, Long.MAX_VALUE, 3_600_000L));
        assertFalse(RetentionPolicy.shouldPurge(0, Long.MAX_VALUE, 3_600_000L));
    }
}
