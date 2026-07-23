package com.zwm.gallery;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;

public final class UpdatePackagePolicyTest {
    @Test
    public void releaseManifestDoesNotRequestAppInstallerCapability() throws Exception {
        String manifest = new String(
                Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);

        assertFalse(
                "Android updates must be downloaded by the system; the app must not request installer capability",
                manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
    }

    @Test
    public void downloadedPackageChecksumUsesExactBytes() throws Exception {
        assertFalse(UpdateDownloadReceiver.sha256(new ByteArrayInputStream(
                "apk".getBytes(StandardCharsets.UTF_8))).isEmpty());
        org.junit.Assert.assertEquals(
                "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04",
                UpdateDownloadReceiver.sha256(new ByteArrayInputStream(
                        "apk".getBytes(StandardCharsets.UTF_8))));
    }
}
