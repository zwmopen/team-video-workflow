package com.zwm.livecore;

import android.net.Uri;
import java.io.File;

/**
 * Result metadata returned by lossless-static / quality-tiered Live Photo conversion.
 */
public final class LiveConvertResult {
    public enum CoverMode {
        LOSSLESS_JPEG_PASSTHROUGH,
        TRANSCODED_NON_JPEG,
        PASSTHROUGH_EXISTING_LIVE
    }

    public final Uri outputUri;
    public final File outputFile;
    public final CoverMode coverMode;
    public final String reencodeReason;
    public final int coverWidth;
    public final int coverHeight;
    public final int coverBytes;
    public final int videoWidth;
    public final int videoHeight;
    public final int videoBitrateBps;
    public final int videoBytes;
    public final LiveQualityTier requestedTier;
    public final LiveQualityTier effectiveTier;
    public final String downgradeReason;
    public final long elapsedMs;

    public LiveConvertResult(
            Uri outputUri,
            File outputFile,
            CoverMode coverMode,
            String reencodeReason,
            int coverWidth,
            int coverHeight,
            int coverBytes,
            int videoWidth,
            int videoHeight,
            int videoBitrateBps,
            int videoBytes,
            LiveQualityTier requestedTier,
            LiveQualityTier effectiveTier,
            String downgradeReason,
            long elapsedMs
    ) {
        this.outputUri = outputUri;
        this.outputFile = outputFile;
        this.coverMode = coverMode;
        this.reencodeReason = reencodeReason;
        this.coverWidth = coverWidth;
        this.coverHeight = coverHeight;
        this.coverBytes = coverBytes;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoBitrateBps = videoBitrateBps;
        this.videoBytes = videoBytes;
        this.requestedTier = requestedTier;
        this.effectiveTier = effectiveTier;
        this.downgradeReason = downgradeReason;
        this.elapsedMs = elapsedMs;
    }

    public boolean isLosslessCover() {
        return coverMode == CoverMode.LOSSLESS_JPEG_PASSTHROUGH || coverMode == CoverMode.PASSTHROUGH_EXISTING_LIVE;
    }
}
