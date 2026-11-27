package com.zwm.gallery;

public final class LiveMotionPhotoPacker {
    private LiveMotionPhotoPacker() {
    }

    public static boolean isMotionPhoto(byte[] raw) {
        return com.zwm.livecore.LiveMotionPhotoPacker.isMotionPhoto(raw);
    }

    public static byte[] pack(byte[] jpegBytes, byte[] mp4Bytes) {
        return com.zwm.livecore.LiveMotionPhotoPacker.pack(jpegBytes, mp4Bytes);
    }
}
