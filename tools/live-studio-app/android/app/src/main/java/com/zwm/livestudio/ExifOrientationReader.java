package com.zwm.livestudio;

import android.graphics.Bitmap;

public final class ExifOrientationReader {
    private ExifOrientationReader() {
    }

    public static int readJpegOrientation(byte[] jpeg) {
        return com.zwm.livecore.LiveExifOrientation.readJpegOrientation(jpeg);
    }

    public static Bitmap applyOrientation(Bitmap src, int orientation) {
        return com.zwm.livecore.LiveExifOrientation.applyOrientation(src, orientation);
    }
}
