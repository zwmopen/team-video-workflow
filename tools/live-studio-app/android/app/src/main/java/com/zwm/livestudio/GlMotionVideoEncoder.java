package com.zwm.livestudio;

import android.graphics.Bitmap;
import com.zwm.livecore.LiveGlVideoEncoder;
import java.io.File;
import java.io.IOException;

public final class GlMotionVideoEncoder {
    private GlMotionVideoEncoder() {
    }

    public static byte[] encode(
            Bitmap bitmap,
            File scratchDir,
            String modeId,
            float ratio,
            float durationSec,
            int fps
    ) throws IOException {
        return LiveGlVideoEncoder.encode(bitmap, scratchDir, modeId, ratio, durationSec, fps);
    }
}
