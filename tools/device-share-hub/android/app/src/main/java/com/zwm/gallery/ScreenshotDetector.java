package com.zwm.gallery;

import java.util.Locale;

final class ScreenshotDetector {
    private ScreenshotDetector() {
    }

    static boolean isScreenshot(String displayName, String relativePath) {
        String value = ((displayName == null ? "" : displayName) + " "
                + (relativePath == null ? "" : relativePath)).toLowerCase(Locale.ROOT);
        return value.contains("screenshot")
                || value.contains("screen_shot")
                || value.contains("screen-shot")
                || value.contains("截屏")
                || value.contains("截图");
    }
}
