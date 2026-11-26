package com.zwm.livestudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionPhotoPackerTest {
    private static byte[] minimalJpeg() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xDA, 0x00, 0x04, 0x00, 0x00,
                0x11, 0x22,
                (byte) 0xFF, (byte) 0xD9
        };
    }

    private static byte[] fakeMp4() {
        return new byte[]{
                0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
                0x00, 0x00, 0x00, 0x00, 'm', 'o', 'o', 'v'
        };
    }

    @Test
    public void packCreatesDetectableMotionPhotoAndRepacksCleanly() {
        byte[] jpeg = minimalJpeg();
        byte[] mp4 = fakeMp4();
        assertFalse(MotionPhotoPacker.isMotionPhoto(jpeg));

        byte[] packed = MotionPhotoPacker.pack(jpeg, mp4);
        assertTrue(MotionPhotoPacker.isMotionPhoto(packed));
        assertEquals((byte) 0xFF, packed[0]);
        assertEquals((byte) 0xD8, packed[1]);

        byte[] mp4v2 = new byte[mp4.length + 4];
        System.arraycopy(mp4, 0, mp4v2, 0, mp4.length);
        mp4v2[mp4.length] = 't';
        mp4v2[mp4.length + 1] = 'e';
        mp4v2[mp4.length + 2] = 's';
        mp4v2[mp4.length + 3] = 't';
        byte[] repacked = MotionPhotoPacker.pack(packed, mp4v2);
        assertTrue(MotionPhotoPacker.isMotionPhoto(repacked));
        assertTrue(repacked.length == packed.length + 4 || Math.abs(repacked.length - packed.length) <= 8);
    }
}
