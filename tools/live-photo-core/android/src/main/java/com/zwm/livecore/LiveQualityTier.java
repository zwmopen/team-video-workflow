package com.zwm.livecore;

/**
 * Dynamic video quality tiers for hardware-accelerated Live Photo encoding.
 * Static JPEG covers remain untouched bit-for-bit regardless of the video tier chosen.
 */
public enum LiveQualityTier {
    SMOOTH("smooth", "流畅模式", "1080P · 3.2Mbps · 省电快速", 1080, 3_200_000, 2_000_000, 4_000_000, 30),
    HD("hd", "高清模式（推荐）", "1440P · 8Mbps · 日常首发默认", 1440, 8_000_000, 6_000_000, 10_000_000, 30),
    ORIGINAL("original", "原画模式", "2048P · 16Mbps · 精品细节拉满", 2048, 16_000_000, 12_000_000, 20_000_000, 30);

    public final String id;
    public final String title;
    public final String summary;
    public final int targetLongSide;
    public final int targetBitrateBps;
    public final int minBitrateBps;
    public final int maxBitrateBps;
    public final int fps;

    LiveQualityTier(
            String id,
            String title,
            String summary,
            int targetLongSide,
            int targetBitrateBps,
            int minBitrateBps,
            int maxBitrateBps,
            int fps
    ) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.targetLongSide = targetLongSide;
        this.targetBitrateBps = targetBitrateBps;
        this.minBitrateBps = minBitrateBps;
        this.maxBitrateBps = maxBitrateBps;
        this.fps = fps;
    }

    public static LiveQualityTier fromId(String id) {
        if (id != null) {
            for (LiveQualityTier tier : values()) {
                if (tier.id.equalsIgnoreCase(id.trim())) {
                    return tier;
                }
            }
        }
        return HD;
    }

    public LiveQualityTier nextLower() {
        switch (this) {
            case ORIGINAL:
                return HD;
            case HD:
                return SMOOTH;
            case SMOOTH:
            default:
                return null;
        }
    }

    /**
     * Never upscales a low-resolution source image above its native pixel dimensions.
     * Aligns dimensions to 16px macroblock multiples (>= 64px when source allows).
     */
    public static int[] computeEncodedSize(int srcW, int srcH, LiveQualityTier tier) {
        LiveQualityTier safeTier = tier != null ? tier : HD;
        int safeW = Math.max(2, srcW);
        int safeH = Math.max(2, srcH);
        int longSide = Math.max(safeW, safeH);
        float scale = 1.0f;
        if (longSide > safeTier.targetLongSide) {
            scale = (float) safeTier.targetLongSide / longSide;
        }
        int rawW = Math.round(safeW * scale);
        int rawH = Math.round(safeH * scale);
        // Never upscale tiny sources; round down/nearest to 16px multiple >= 32px
        int outW = Math.max(32, (Math.min(safeW, rawW) / 16) * 16);
        int outH = Math.max(32, (Math.min(safeH, rawH) / 16) * 16);
        if (outW == 0) outW = 32;
        if (outH == 0) outH = 32;
        return new int[]{outW, outH};
    }

    public static int computeBitrateBps(int outW, int outH, LiveQualityTier tier) {
        LiveQualityTier safeTier = tier != null ? tier : HD;
        long pixels = Math.max(1L, (long) outW * outH);
        long refPixels = (long) safeTier.targetLongSide * Math.max(1L, Math.round(safeTier.targetLongSide * 0.75));
        double ratio = Math.max(0.25, Math.min(1.5, (double) pixels / refPixels));
        int scaled = (int) Math.round(safeTier.targetBitrateBps * ratio);
        return Math.max(safeTier.minBitrateBps, Math.min(safeTier.maxBitrateBps, scaled));
    }
}
