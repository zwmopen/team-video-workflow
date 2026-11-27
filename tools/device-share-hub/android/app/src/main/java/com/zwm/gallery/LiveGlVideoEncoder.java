package com.zwm.gallery;

import android.graphics.Bitmap;
import com.zwm.livecore.LiveQualityTier;
import java.io.File;
import java.io.IOException;

public final class LiveGlVideoEncoder {
    private LiveGlVideoEncoder() {
    }

    public static int[] computeEncodedSize(int srcW, int srcH) {
        return LiveQualityTier.computeEncodedSize(srcW, srcH, LiveQualityTier.HD);
    }

    public static byte[] encode(
            Bitmap bitmap,
            File scratchDir,
            String modeId,
            float ratio,
            float durationSec,
            int fps
    ) throws IOException {
        return com.zwm.livecore.LiveGlVideoEncoder.encode(bitmap, scratchDir, modeId, ratio, durationSec, fps);
    }
}
