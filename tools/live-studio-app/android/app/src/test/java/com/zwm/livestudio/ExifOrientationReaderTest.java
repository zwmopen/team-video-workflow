package com.zwm.livestudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExifOrientationReaderTest {
    @Test
    public void returnsNormalWhenNoExifPresent() {
        byte[] nonExif = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        assertEquals(1, ExifOrientationReader.readJpegOrientation(nonExif));
    }

    @Test
    public void parsesLittleEndianRotate90Tag() {
        // Minimal JPEG with APP1 Exif ("Exif\0\0" + II TIFF + 1 IFD0 entry 0x0112 = 6)
        byte[] jpeg = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x20,
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9
        };
        assertEquals(6, ExifOrientationReader.readJpegOrientation(jpeg));
    }
}
