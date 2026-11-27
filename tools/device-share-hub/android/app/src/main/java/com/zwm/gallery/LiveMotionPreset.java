package com.zwm.gallery;

public final class LiveMotionPreset {
    public static final String DEFAULT_MODE = com.zwm.livecore.LiveMotionPreset.DEFAULT_MODE;
    public static final float DEFAULT_RATIO = com.zwm.livecore.LiveMotionPreset.DEFAULT_RATIO;
    public static final float DEFAULT_DURATION_SEC = com.zwm.livecore.LiveMotionPreset.DEFAULT_DURATION_SEC;
    public static final int DEFAULT_FPS = com.zwm.livecore.LiveMotionPreset.DEFAULT_FPS;

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
            this.zoom = zoom;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    public static final PresetInfo[] PRESETS;

    static {
        com.zwm.livecore.LiveMotionPreset.PresetInfo[] core = com.zwm.livecore.LiveMotionPreset.PRESETS;
        PRESETS = new PresetInfo[core.length];
        for (int i = 0; i < core.length; i++) {
            PRESETS[i] = new PresetInfo(core[i].id, core[i].title, core[i].desc);
        }
    }

    private LiveMotionPreset() {
    }

    public static String normalizeMode(String modeId) {
        return com.zwm.livecore.LiveMotionPreset.normalizeMode(modeId);
    }

    public static PresetInfo findById(String modeId) {
        com.zwm.livecore.LiveMotionPreset.PresetInfo info = com.zwm.livecore.LiveMotionPreset.findById(modeId);
        return new PresetInfo(info.id, info.title, info.desc);
    }

    public static float clampRatio(float ratio) {
        return com.zwm.livecore.LiveMotionPreset.clampRatio(ratio);
    }

    public static float clampDuration(float sec) {
        return com.zwm.livecore.LiveMotionPreset.clampDuration(sec);
    }

    public static FrameTransform computeFrame(String modeId, float ratioInput, int frameIndex, int totalFrames) {
        com.zwm.livecore.LiveMotionPreset.FrameTransform tf =
                com.zwm.livecore.LiveMotionPreset.computeFrame(modeId, ratioInput, frameIndex, totalFrames);
        return new FrameTransform(tf.zoom, tf.offsetX, tf.offsetY);
    }
}
