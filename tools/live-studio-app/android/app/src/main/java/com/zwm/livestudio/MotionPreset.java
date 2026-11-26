package com.zwm.livestudio;

public final class MotionPreset {
    public static final String DEFAULT_MODE = "natural_hold";
    public static final float DEFAULT_RATIO = 1.018f;
    public static final float DEFAULT_DURATION_SEC = 1.6f;
    public static final int DEFAULT_FPS = 30;

    public static final class PresetInfo {
        public final String id;
        public final String title;
        public final String desc;

        PresetInfo(String id, String title, String desc) {
            this.id = id;
            this.title = title;
            this.desc = desc;
        }
    }

    public static final class FrameTransform {
        public final float zoom;
        public final float offsetX;
        public final float offsetY;

        public FrameTransform(float zoom, float offsetX, float offsetY) {
            this.zoom = Math.max(1.0f, zoom);
            this.offsetX = clamp01(offsetX);
            this.offsetY = clamp01(offsetY);
        }
    }

    public static final PresetInfo[] PRESETS = {
            new PresetInfo("natural_hold", "自然手持微动（推荐）", "模拟真实持机拍摄的轻微呼吸稳态，最接近真机实拍"),
            new PresetInfo("subtle_still", "几乎静止（极微呼吸）", "极小幅度变化，几乎不改构图，仅保留实况标识"),
            new PresetInfo("zoom_in", "轻柔推近", "画面极轻微平滑向前推近（缓入缓出）"),
            new PresetInfo("zoom_out", "轻柔拉远", "画面极轻微平滑向后舒展（缓入缓出）"),
            new PresetInfo("pan_up", "微幅上移", "画面极轻微平滑向上移动"),
            new PresetInfo("pan_down", "微幅下移", "画面极轻微平滑向下移动"),
            new PresetInfo("pan_left", "微幅左移", "画面极轻微平滑向左移动"),
            new PresetInfo("pan_right", "微幅右移", "画面极轻微平滑向右移动"),
            new PresetInfo("handheld", "温和手持轻晃", "克制的小幅自然手持微晃")
    };

    private MotionPreset() {
    }

    public static String normalizeMode(String modeId) {
        if (modeId != null) {
            for (PresetInfo p : PRESETS) {
                if (p.id.equals(modeId)) return p.id;
            }
        }
        return DEFAULT_MODE;
    }

    public static PresetInfo findById(String modeId) {
        String safe = normalizeMode(modeId);
        for (PresetInfo p : PRESETS) {
            if (p.id.equals(safe)) return p;
        }
        return PRESETS[0];
    }

    public static float clampRatio(float ratio) {
        if (Float.isNaN(ratio)) return DEFAULT_RATIO;
        return Math.max(1.005f, Math.min(1.08f, ratio));
    }

    public static float clampDuration(float sec) {
        if (Float.isNaN(sec)) return DEFAULT_DURATION_SEC;
        return Math.max(1.0f, Math.min(3.0f, sec));
    }

    public static FrameTransform computeFrame(String modeId, float ratioInput, int frameIndex, int totalFrames) {
        String mode = normalizeMode(modeId);
        float ratio = clampRatio(ratioInput);
        int den = Math.max(1, totalFrames - 1);
        double p = Math.max(0.0, Math.min(1.0, (double) frameIndex / den));
        double ease = (1.0 - Math.cos(Math.PI * p)) / 2.0;

        switch (mode) {
            case "natural_hold": {
                double delta = Math.max(0.008, ratio - 1.0);
                double z = 1.0 + delta * (0.15 + 0.85 * ease);
                double ox = 0.5 + 0.14 * Math.sin(Math.PI * p);
                double oy = 0.5 + 0.10 * Math.sin(Math.PI * p);
                return new FrameTransform((float) z, (float) ox, (float) oy);
            }
            case "subtle_still": {
                double delta = Math.max(0.005, (ratio - 1.0) * 0.45);
                return new FrameTransform((float) (1.0 + delta * ease), 0.5f, 0.5f);
            }
            case "zoom_out": {
                double z = ratio - (ratio - 1.0) * ease;
                return new FrameTransform((float) z, 0.5f, 0.5f);
            }
            case "pan_up":
                return new FrameTransform(ratio, 0.5f, (float) (1.0 - ease));
            case "pan_down":
                return new FrameTransform(ratio, 0.5f, (float) ease);
            case "pan_left":
                return new FrameTransform(ratio, (float) (1.0 - ease), 0.5f);
            case "pan_right":
                return new FrameTransform(ratio, (float) ease, 0.5f);
            case "handheld": {
                double env = Math.sin(Math.PI * p);
                double z = 1.0 + (ratio - 1.0) * (0.45 + 0.35 * env);
                double ox = 0.5 + 0.20 * Math.sin(2.0 * Math.PI * p) * env;
                double oy = 0.5 + 0.14 * Math.sin(1.5 * Math.PI * p) * env;
                return new FrameTransform((float) z, (float) ox, (float) oy);
            }
            case "zoom_in":
            default: {
                double z = 1.0 + (ratio - 1.0) * ease;
                return new FrameTransform((float) z, 0.5f, 0.5f);
            }
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
