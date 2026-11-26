package com.zwm.livestudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionPresetTest {
    @Test
    public void defaultAndClampStaySubtleAndBounded() {
        assertEquals("natural_hold", MotionPreset.DEFAULT_MODE);
        assertEquals(1.018f, MotionPreset.DEFAULT_RATIO, 0.0001f);
        assertEquals(1.6f, MotionPreset.DEFAULT_DURATION_SEC, 0.0001f);
        assertEquals(9, MotionPreset.PRESETS.length);

        assertEquals(1.005f, MotionPreset.clampRatio(0.5f), 0.0001f);
        assertEquals(1.08f, MotionPreset.clampRatio(2.0f), 0.0001f);
        assertEquals(1.0f, MotionPreset.clampDuration(0.1f), 0.0001f);
        assertEquals(3.0f, MotionPreset.clampDuration(10f), 0.0001f);
    }

    @Test
    public void allPresetsKeepCropInsideUnitSquare() {
        int frames = 48;
        for (MotionPreset.PresetInfo p : MotionPreset.PRESETS) {
            for (int f = 0; f < frames; f++) {
                MotionPreset.FrameTransform tf = MotionPreset.computeFrame(p.id, 1.02f, f, frames);
                assertTrue("zoom >= 1.0 for " + p.id, tf.zoom >= 1.0f);
                float cropW = 1.0f / tf.zoom;
                float u0 = (1.0f - cropW) * tf.offsetX;
                float v0 = (1.0f - cropW) * tf.offsetY;
                assertTrue(u0 >= 0f && u0 + cropW <= 1.0001f);
                assertTrue(v0 >= 0f && v0 + cropW <= 1.0001f);
            }
        }
    }
}
