package com.zwm.gallery;

import android.graphics.Bitmap;

public final class LiveExifOrientation {
    private LiveExifOrientation() {
    }

    public static int readJpegOrientation(byte[] jpeg) {
        return com.zwm.livecore.LiveExifOrientation.readJpegOrientation(jpeg);
    }

    public static Bitmap applyOrientation(Bitmap src, int orientation) {
        return com.zwm.livecore.LiveExifOrientation.applyOrientation(src, orientation);
    }
}
