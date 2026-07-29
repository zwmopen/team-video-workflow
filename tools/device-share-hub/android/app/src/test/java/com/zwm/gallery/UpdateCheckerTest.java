package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UpdateCheckerTest {
    @Test
    public void comparesSemanticVersionsUsedByUpdateManifest() {
        assertTrue(UpdateChecker.isNewer("0.4.8", "0.4.7"));
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"));
        assertFalse(UpdateChecker.isNewer("0.4.3", "0.4.3"));
        assertFalse(UpdateChecker.isNewer("0.4.2", "0.4.3"));
    }

    @Test
    public void updateDownloadAlwaysUsesApkExtension() {
        assertEquals("相册-Android-0.5.10.apk", UpdateChecker.updateFileName("0.5.10"));
        assertEquals("相册-Android-latest.apk", UpdateChecker.updateFileName(""));
        assertEquals("相册-Android-0.5.10_beta.apk",
                UpdateChecker.updateFileName("0.5.10 beta"));
    }
}
