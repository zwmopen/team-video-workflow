package com.zwm.livecore;

public final class LiveMotionPreset {
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

    // Reference 60-frame curve: scale delta and output-center translation / width or height.
    private static final double[] PUSH_Z = {0.000000, 0.005147, 0.010059, 0.015035, 0.020014, 0.024423, 0.029570, 0.034068, 0.038547, 0.043122,
        0.047424, 0.051740, 0.055742, 0.059774, 0.063531, 0.067648, 0.071081, 0.074225, 0.077471, 0.080498,
        0.083755, 0.086177, 0.088734, 0.090810, 0.092911, 0.094454, 0.096326, 0.097324, 0.098396, 0.098922,
        0.099791, 0.099937, 0.100696, 0.101306, 0.102127, 0.103062, 0.103789, 0.104631, 0.105781, 0.106773,
        0.106803, 0.107560, 0.108322, 0.109408, 0.110110, 0.110823, 0.111166, 0.111724, 0.112540, 0.112768,
        0.113310, 0.113580, 0.114407, 0.114073, 0.114779, 0.114588, 0.114858, 0.115460, 0.115503, 0.115388};
    private static final double[] PUSH_X = {0.000000, 0.001051, 0.000706, 0.000370, 0.001428, 0.000721, 0.001817, 0.001116, 0.001828, 0.001107,
        0.000033, 0.000758, -0.000358, 0.000027, 0.000410, 0.000783, 0.002294, 0.002302, 0.002298, 0.000778,
        0.000778, 0.000372, 0.000007, -0.000736, 0.000401, 0.001168, 0.001882, 0.002320, 0.002744, 0.001626,
        0.000460, -0.001149, -0.000786, 0.001178, 0.000026, -0.001123, -0.003840, -0.005091, -0.006197, -0.007365,
        -0.008945, -0.010121, -0.009741, -0.010887, -0.010531, -0.011712, -0.014800, -0.015999, -0.017265, -0.020384,
        -0.021461, -0.023100, -0.022755, -0.024382, -0.024274, -0.025846, -0.025574, -0.027083, -0.028628, -0.031778};
    private static final double[] PUSH_Y = {0.000000, 0.000512, 0.000790, 0.001052, 0.000225, 0.000248, 0.000548, 0.000537, 0.000528, 0.001633,
        0.001690, 0.001402, 0.001110, 0.000848, 0.000290, 0.000021, 0.000586, -0.000272, 0.000008, 0.001440,
        0.001696, 0.001703, 0.001715, 0.001769, 0.001436, 0.000857, 0.000588, 0.000006, -0.000558, -0.000250,
        0.000039, 0.000017, 0.001445, 0.001751, 0.002010, 0.001470, 0.001720, 0.000834, 0.000278, 0.000525,
        -0.000278, -0.001148, -0.000874, -0.000277, 0.000015, 0.001469, 0.001777, 0.001759, 0.002020, 0.002399,
        0.001468, 0.000277, 0.000601, -0.000580, -0.000326, -0.000255, -0.000317, -0.000006, 0.001204, 0.001151};
    public static final String PUSH_DRIFT_MODE = "handheld_push_drift_v1";
    public static final float PUSH_DRIFT_RATIO = 1.116f;
    public static final float PUSH_DRIFT_DURATION = 2.0f;

    private static double sample(double[] curve, double p) {
        double i = p * (curve.length - 1);
        int lo = (int) i;
        return curve[lo] + (curve[Math.min(lo + 1, curve.length - 1)] - curve[lo]) * (i - lo);
    }

    public static final PresetInfo[] PRESETS = {
            new PresetInfo("natural_hold", "自然手持微动（推荐）", "模拟真实持机拍摄的轻微呼吸稳态，最接近真机实拍"),
            new PresetInfo("handheld_push_drift_v1", "自然推近·轻晃侧移", "前快后慢推近，后段轻微侧移，参考实况样本曲线"),
            new PresetInfo("subtle_still", "几乎静止（极微呼吸）", "极小幅度变化，几乎不改构图，仅保留实况标识"),
            new PresetInfo("zoom_in", "轻柔推近", "画面极轻微平滑向前推近（缓入缓出）"),
            new PresetInfo("zoom_out", "轻柔拉远", "画面极轻微平滑向后舒展（缓入缓出）"),
            new PresetInfo("pan_up", "微幅上移", "画面极轻微平滑向上移动"),
            new PresetInfo("pan_down", "微幅下移", "画面极轻微平滑向下移动"),
            new PresetInfo("pan_left", "微幅左移", "画面极轻微平滑向左移动"),
            new PresetInfo("pan_right", "微幅右移", "画面极轻微平滑向右移动"),
            new PresetInfo("handheld", "温和手持轻晃", "克制的小幅自然手持微晃")
    };

    private LiveMotionPreset() {
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
        return Math.max(1.005f, Math.min(1.12f, ratio));
    }

    public static float clampDuration(float sec) {
        if (Float.isNaN(sec)) return DEFAULT_DURATION_SEC;
        return Math.max(1.0f, Math.min(3.0f, sec));
    }

    public static FrameTransform computeFrame(String modeId, float ratioInput, int frameIndex, int totalFrames) {
        String mode = normalizeMode(modeId);
        float ratio = clampRatio(ratioInput);
        if (!PUSH_DRIFT_MODE.equals(mode)) ratio = Math.min(1.08f, ratio);
        int den = Math.max(1, totalFrames - 1);
        double p = Math.max(0.0, Math.min(1.0, (double) frameIndex / den));
        double ease = (1.0 - Math.cos(Math.PI * p)) / 2.0;

        switch (mode) {
            case "handheld_push_drift_v1": {
                double gain = (ratio - 1.0) / 0.115503;
                double delta = sample(PUSH_Z, p) * gain;
                if (delta < 1e-9) return new FrameTransform(1f, 0.5f, 0.5f);
                double ox = Math.max(0.0, Math.min(1.0, 0.5 - sample(PUSH_X, p) * gain / delta));
                double oy = Math.max(0.0, Math.min(1.0, 0.5 - sample(PUSH_Y, p) * gain / delta));
                return new FrameTransform((float) (1.0 + delta), (float) ox, (float) oy);
            }
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
