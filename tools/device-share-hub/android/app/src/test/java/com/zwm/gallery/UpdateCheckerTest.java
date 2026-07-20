package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UpdateCheckerTest {
    @Test
    public void comparesSemanticVersionsUsedByUpdateManifest() {
        assertTrue(UpdateChecker.isNewer("0.4.7", "0.4.6"));
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"));
        assertFalse(UpdateChecker.isNewer("0.4.3", "0.4.3"));
        assertFalse(UpdateChecker.isNewer("0.4.2", "0.4.3"));
    }
}
