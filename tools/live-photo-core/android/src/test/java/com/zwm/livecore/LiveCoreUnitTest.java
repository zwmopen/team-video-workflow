package com.zwm.livecore;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class LiveCoreUnitTest {
    private static byte[] buildSyntheticJpegWithExifAndIcc() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // SOI
        out.write(0xFF); out.write(0xD8);
        // APP1 EXIF marker
        byte[] exif = "Exif\0\0MM\0*\0\0\0\u0008\0\0".getBytes(StandardCharsets.ISO_8859_1);
        out.write(0xFF); out.write(0xE1);
        int exifLen = exif.length + 2;
        out.write((exifLen >> 8) & 0xFF); out.write(exifLen & 0xFF);
        out.write(exif, 0, exif.length);
        // APP2 ICC_PROFILE marker
        byte[] icc = "ICC_PROFILE\0\u0001\u0001TEST_ICC_PROFILE".getBytes(StandardCharsets.US_ASCII);
        out.write(0xFF); out.write(0xE2);
        int iccLen = icc.length + 2;
        out.write((iccLen >> 8) & 0xFF); out.write(iccLen & 0xFF);
        out.write(icc, 0, icc.length);
        // SOF0 marker
        out.write(0xFF); out.write(0xC0); out.write(0x00); out.write(0x05);
        out.write(0x08); out.write(0x01); out.write(0x00);
        // SOS + entropy-coded scan + EOI
        out.write(0xFF); out.write(0xDA);
        byte[] scanBody = new byte[]{0x00, 0x06, 0x01, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};
        out.write(scanBody, 0, scanBody.length);
        out.write(0xFF); out.write(0xD9);
        return out.toByteArray();
    }

    private static byte[] buildFakeMp4() {
        byte[] ftyp = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 1, 'm', 'p', '4', '2', 'm', 'd', 'a', 't'};
        byte[] out = new byte[32];
        System.arraycopy(ftyp, 0, out, 0, ftyp.length);
        return out;
    }

    @Test
    public void testLosslessJpegPackPreservesExactScanAndAppSegments() {
        byte[] rawJpg = buildSyntheticJpegWithExifAndIcc();
        byte[] fakeMp4 = buildFakeMp4();
        byte[] origSos = LiveMotionPhotoPacker.extractSosScanBytes(rawJpg);
        assertTrue(origSos.length > 0);

        byte[] packed = LiveMotionPhotoPacker.pack(rawJpg, fakeMp4);
        assertTrue(LiveMotionPhotoPacker.isMotionPhoto(packed));

        byte[] packedSos = LiveMotionPhotoPacker.extractSosScanBytes(packed);
        assertArrayEquals("SOS entropy scan bytes must remain bit-exact", origSos, packedSos);

        byte[] extractedStill = LiveMotionPhotoPacker.extractStillJpeg(packed);
        assertEquals(rawJpg.length, extractedStill.length);
        assertArrayEquals("Stripping XMP/trailer must round-trip exact original JPEG", rawJpg, extractedStill);

        String packedLatin = new String(packed, 0, packed.length - fakeMp4.length, StandardCharsets.ISO_8859_1);
        assertTrue("ICC profile must stay preserved", packedLatin.contains("ICC_PROFILE"));
        assertTrue("EXIF header must stay preserved", packedLatin.contains("Exif\0\0MM"));
    }

    @Test
    public void testRepackReplacesOldMotionTrailerWithoutCorruptingScan() {
        byte[] rawJpg = buildSyntheticJpegWithExifAndIcc();
        byte[] origSos = LiveMotionPhotoPacker.extractSosScanBytes(rawJpg);
        byte[] first = LiveMotionPhotoPacker.pack(rawJpg, buildFakeMp4());
        byte[] secondMp4 = Arrays.copyOf(buildFakeMp4(), 48);
        byte[] second = LiveMotionPhotoPacker.pack(first, secondMp4);

        assertTrue(LiveMotionPhotoPacker.isMotionPhoto(second));
        assertArrayEquals(origSos, LiveMotionPhotoPacker.extractSosScanBytes(second));
        assertArrayEquals(rawJpg, LiveMotionPhotoPacker.extractStillJpeg(second));
    }

    @Test
    public void testQualityTiersNeverUpscaleAndScaleBitrateSafely() {
        int[] lowRes = LiveQualityTier.computeEncodedSize(640, 480, LiveQualityTier.ORIGINAL);
        assertTrue(lowRes[0] <= 640 && lowRes[1] <= 480);

        int[] hiSmooth = LiveQualityTier.computeEncodedSize(3024, 4032, LiveQualityTier.SMOOTH);
        int smoothLong = Math.max(hiSmooth[0], hiSmooth[1]);
        assertTrue("1080P tier must align to 16px <= 1080", smoothLong <= 1080 && smoothLong >= 1072);
        assertEquals(0, hiSmooth[0] % 16);
        assertEquals(0, hiSmooth[1] % 16);

        int[] hiHd = LiveQualityTier.computeEncodedSize(3024, 4032, LiveQualityTier.HD);
        assertEquals(1440, Math.max(hiHd[0], hiHd[1]));

        int[] hiOrig = LiveQualityTier.computeEncodedSize(3024, 4032, LiveQualityTier.ORIGINAL);
        assertEquals(2048, Math.max(hiOrig[0], hiOrig[1]));

        assertEquals(LiveQualityTier.HD, LiveQualityTier.ORIGINAL.nextLower());
        assertEquals(LiveQualityTier.SMOOTH, LiveQualityTier.HD.nextLower());
        assertNull(LiveQualityTier.SMOOTH.nextLower());
    }

    @Test
    public void testMotionPresetsStayWithinBounds() {
        assertEquals("handheld_push_drift_v1", LiveMotionPreset.PRESETS[1].id);
        LiveMotionPreset.FrameTransform start = LiveMotionPreset.computeFrame("handheld_push_drift_v1", 1.116f, 0, 60);
        LiveMotionPreset.FrameTransform end = LiveMotionPreset.computeFrame("handheld_push_drift_v1", 1.116f, 59, 60);
        assertEquals(1.0f, start.zoom, 0.0001f);
        assertEquals(1.1159f, end.zoom, 0.0003f);
        assertTrue(end.offsetX > 0.75f && end.offsetX < 0.80f);
        assertTrue(end.offsetY > 0.48f && end.offsetY < 0.50f);
        for (LiveMotionPreset.PresetInfo preset : LiveMotionPreset.PRESETS) {
            for (int f = 0; f < 48; f++) {
                LiveMotionPreset.FrameTransform tf = LiveMotionPreset.computeFrame(preset.id, 1.018f, f, 48);
                assertTrue(tf.zoom >= 1.0f && tf.zoom <= 1.08f);
                assertTrue(tf.offsetX >= 0f && tf.offsetX <= 1f);
                assertTrue(tf.offsetY >= 0f && tf.offsetY <= 1f);
            }
        }
    }
}
