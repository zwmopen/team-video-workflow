package com.zwm.livestudio;

public final class MotionPhotoPacker {
    private MotionPhotoPacker() {
    }

    public static boolean isMotionPhoto(byte[] raw) {
        return com.zwm.livecore.LiveMotionPhotoPacker.isMotionPhoto(raw);
    }

    public static byte[] pack(byte[] jpegBytes, byte[] mp4Bytes) {
        return com.zwm.livecore.LiveMotionPhotoPacker.pack(jpegBytes, mp4Bytes);
    }
}
