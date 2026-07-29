package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScreenshotDetectorTest {
    @Test public void recognizesCommonAndroidScreenshotNamesAndFolders() {
        assertTrue(ScreenshotDetector.isScreenshot("Screenshot_20260729.png", "Pictures/"));
        assertTrue(ScreenshotDetector.isScreenshot("IMG_1.png", "Pictures/截图/"));
        assertTrue(ScreenshotDetector.isScreenshot("截屏_20260729.jpg", "DCIM/"));
        assertFalse(ScreenshotDetector.isScreenshot("IMG_20260729.jpg", "DCIM/Camera/"));
    }
}
