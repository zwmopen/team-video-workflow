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
    public void appDownloaderOnlyAppendsMatchingPartialResponses() {
        assertTrue(AppUpdateService.shouldAppend(1024, 206, "bytes 1024-2047/4096"));
        assertFalse(AppUpdateService.shouldAppend(1024, 200, null));
        assertFalse(AppUpdateService.shouldAppend(1024, 206, "bytes 0-2047/4096"));
        assertFalse(AppUpdateService.shouldAppend(0, 206, "bytes 0-2047/4096"));
    }

    @Test
    public void appDownloaderOnlyFollowsExpectedRedirectCodes() {
        assertTrue(AppUpdateService.isRedirect(301));
        assertTrue(AppUpdateService.isRedirect(302));
        assertTrue(AppUpdateService.isRedirect(307));
        assertTrue(AppUpdateService.isRedirect(308));
        assertFalse(AppUpdateService.isRedirect(200));
        assertFalse(AppUpdateService.isRedirect(404));
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
