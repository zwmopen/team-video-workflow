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
        assertEquals("album-Android-0.5.10.apk", UpdateChecker.updateFileName("0.5.10"));
        assertEquals("album-Android-latest.apk", UpdateChecker.updateFileName(""));
        assertEquals("album-Android-0.5.10_beta.apk",
                UpdateChecker.updateFileName("0.5.10 beta"));
    }

    @Test
    public void selectsChecksumForExactApkAsset() {
        String hash = "df5d639dabc3ba0f1781225d34bfa65bc92c1d31b6093414198557684f623f85";
        String manifest = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  old.apk\n"
                + hash + "  album-Android-v0.6.12.apk\n";
        assertEquals(hash, UpdateChecker.checksumFor(
                manifest, "album-Android-v0.6.12.apk"));
        assertEquals("", UpdateChecker.checksumFor(manifest, "missing.apk"));
    }
}
