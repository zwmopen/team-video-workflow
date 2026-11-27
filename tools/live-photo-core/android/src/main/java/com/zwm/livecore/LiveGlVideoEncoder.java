package com.zwm.livecore;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Hardware-accelerated offline MP4 generator using OpenGL ES 2.0 (bilinear sub-pixel pan/zoom)
 * piped directly into MediaCodec + MediaMuxer with automatic tier fallback if device encoder
 * rejects higher resolutions or bitrates.
 */
public final class LiveGlVideoEncoder {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    public static final class EncodedVideoResult {
        public final byte[] mp4Bytes;
        public final int width;
        public final int height;
        public final int bitrateBps;
        public final LiveQualityTier requestedTier;
        public final LiveQualityTier effectiveTier;
        public final String downgradeReason;

        public EncodedVideoResult(
                byte[] mp4Bytes,
                int width,
                int height,
                int bitrateBps,
                LiveQualityTier requestedTier,
                LiveQualityTier effectiveTier,
                String downgradeReason
        ) {
            this.mp4Bytes = mp4Bytes;
            this.width = width;
            this.height = height;
            this.bitrateBps = bitrateBps;
            this.requestedTier = requestedTier;
            this.effectiveTier = effectiveTier;
            this.downgradeReason = downgradeReason;
        }
    }

    private static final String VERTEX_SHADER =
            "attribute vec2 aPos;\n"
                    + "attribute vec2 aTex;\n"
                    + "uniform vec4 uCrop;\n"
                    + "varying vec2 vTex;\n"
                    + "void main() {\n"
                    + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
                    + "  vTex = uCrop.xy + aTex * uCrop.zw;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform sampler2D uTex;\n"
                    + "varying vec2 vTex;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTex, vTex);\n"
                    + "}\n";

    private static final float[] QUAD_POS = {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
    };

    private static final float[] QUAD_TEX = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    private LiveGlVideoEncoder() {
    }

    public static int[] computeEncodedSize(int srcW, int srcH, LiveQualityTier tier) {
        return LiveQualityTier.computeEncodedSize(srcW, srcH, tier);
    }

    public static EncodedVideoResult encodeWithFallback(
            Bitmap bitmap,
            File scratchDir,
            String modeId,
            LiveQualityTier requestedTier,
            float ratio,
            float durationSec,
            int fps
    ) throws IOException {
        LiveQualityTier req = requestedTier != null ? requestedTier : LiveQualityTier.HD;
        LiveQualityTier cur = req;
        IOException lastError = null;
        StringBuilder downgradeNote = new StringBuilder();
        while (cur != null) {
            try {
                int[] dims = LiveQualityTier.computeEncodedSize(bitmap.getWidth(), bitmap.getHeight(), cur);
                int bitrate = LiveQualityTier.computeBitrateBps(dims[0], dims[1], cur);
                byte[] mp4 = encodeSingle(bitmap, scratchDir, modeId, dims[0], dims[1], bitrate, ratio, durationSec, fps);
                return new EncodedVideoResult(
                        mp4,
                        dims[0],
                        dims[1],
                        bitrate,
                        req,
                        cur,
                        downgradeNote.length() > 0 ? downgradeNote.toString() : null
                );
            } catch (Exception err) {
                lastError = err instanceof IOException ? (IOException) err : new IOException(err.getMessage(), err);
                LiveQualityTier lower = cur.nextLower();
                if (lower == null) {
                    throw lastError;
                }
                if (downgradeNote.length() > 0) downgradeNote.append(" -> ");
                downgradeNote.append(cur.id).append(" 编码受限(").append(err.getMessage()).append(")降级至 ").append(lower.id);
                cur = lower;
            }
        }
        throw lastError != null ? lastError : new IOException("视频编码失败");
    }

    public static byte[] encode(
            Bitmap bitmap,
            File scratchDir,
            String modeId,
            float ratio,
            float durationSec,
            int fps
    ) throws IOException {
        return encodeWithFallback(bitmap, scratchDir, modeId, LiveQualityTier.HD, ratio, durationSec, fps).mp4Bytes;
    }

    private static byte[] encodeSingle(
            Bitmap bitmap,
            File scratchDir,
            String modeId,
            int outW,
            int outH,
            int bitRate,
            float ratio,
            float durationSec,
            int fps
    ) throws IOException {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("图片无效，无法生成视频");
        }
        int safeFps = Math.max(24, Math.min(30, fps));
        float safeDur = LiveMotionPreset.clampDuration(durationSec);
        int totalFrames = Math.max(12, Math.round(safeDur * safeFps));

        if (!scratchDir.exists()) scratchDir.mkdirs();
        File tmpMp4 = File.createTempFile("livestudio_", ".mp4", scratchDir);
        MediaCodec codec = null;
        Surface inputSurface = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        int textureId = 0;
        int program = 0;

        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, safeFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            try {
                MediaCodecInfo.VideoCapabilities caps = codec.getCodecInfo()
                        .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .getVideoCapabilities();
                if (caps != null && !caps.isSizeSupported(outW, outH)) {
                    throw new IOException("设备硬件编码不支持 " + outW + "x" + outH);
                }
            } catch (IOException ioe) {
                throw ioe;
            } catch (Exception ignored) {
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();

            muxer = new MediaMuxer(tmpMp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int trackIndex = -1;

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("无法获取 EGL Display");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw new IllegalStateException("EGL 初始化失败");
            }
            int[] cfgAttrib = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numCfg = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttrib, 0, configs, 0, 1, numCfg, 0) || numCfg[0] == 0) {
                throw new IllegalStateException("找不到支持的硬件录制 EGLConfig");
            }
            int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("创建 EGLContext 失败");
            int[] surfAttrib = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], inputSurface, surfAttrib, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("创建编码 EGLSurface 失败");
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new IllegalStateException("切换 EGLContext 失败");
            }

            program = buildProgram();
            GLES20.glUseProgram(program);
            int aPos = GLES20.glGetAttribLocation(program, "aPos");
            int aTex = GLES20.glGetAttribLocation(program, "aTex");
            int uCrop = GLES20.glGetUniformLocation(program, "uCrop");
            int uTex = GLES20.glGetUniformLocation(program, "uTex");

            int[] texArr = new int[1];
            GLES20.glGenTextures(1, texArr, 0);
            textureId = texArr[0];
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLES20.glUniform1i(uTex, 0);

            FloatBuffer posBuf = asFloatBuffer(QUAD_POS);
            FloatBuffer texBuf = asFloatBuffer(QUAD_TEX);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf);
            GLES20.glViewport(0, 0, outW, outH);

            MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
            for (int frame = 0; frame < totalFrames; frame++) {
                LiveMotionPreset.FrameTransform tf = LiveMotionPreset.computeFrame(modeId, ratio, frame, totalFrames);
                float cropW = 1.0f / tf.zoom;
                float cropH = 1.0f / tf.zoom;
                float u0 = (1.0f - cropW) * tf.offsetX;
                float v0 = (1.0f - cropH) * tf.offsetY;
                GLES20.glUniform4f(uCrop, u0, v0, cropW, cropH);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                long ptsNs = (frame * 1_000_000_000L) / safeFps;
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                int[] muxState = drainEncoder(codec, muxer, bufInfo, trackIndex, muxerStarted, false);
                trackIndex = muxState[0];
                muxerStarted = muxState[1] == 1;
            }

            codec.signalEndOfInputStream();
            drainEncoder(codec, muxer, bufInfo, trackIndex, muxerStarted, true);
            muxerStarted = true;

            try { muxer.stop(); } catch (Exception ignored) { }
            try { muxer.release(); } catch (Exception ignored) { }
            muxer = null;

            return readFileBytes(tmpMp4);
        } finally {
            if (textureId != 0 && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try { GLES20.glDeleteTextures(1, new int[]{textureId}, 0); } catch (Exception ignored) { }
            }
            if (program != 0 && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try { GLES20.glDeleteProgram(program); } catch (Exception ignored) { }
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglTerminate(eglDisplay);
                } catch (Exception ignored) { }
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            if (inputSurface != null) {
                try { inputSurface.release(); } catch (Exception ignored) { }
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (tmpMp4.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmpMp4.delete();
            }
        }
    }

    private static int[] drainEncoder(
            MediaCodec codec,
            MediaMuxer muxer,
            MediaCodec.BufferInfo info,
            int trackIndex,
            boolean muxerStarted,
            boolean endOfStream
    ) {
        long timeoutUs = endOfStream ? 10_000L : 0L;
        int eosSpins = 0;
        while (true) {
            int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
                if (++eosSpins > 400) break;
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (outIdx >= 0) {
                ByteBuffer encoded = codec.getOutputBuffer(outIdx);
                if (encoded != null && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, encoded, info);
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
        return new int[]{trackIndex, muxerStarted ? 1 : 0};
    }

    private static int buildProgram() {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] link = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (link[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new IllegalStateException("Shader 链接失败: " + log);
        }
        return prog;
    }

    private static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader 编译失败: " + log);
        }
        return shader;
    }

    private static FloatBuffer asFloatBuffer(float[] values) {
        FloatBuffer buf = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buf.put(values);
        buf.position(0);
        return buf;
    }

    private static byte[] readFileBytes(File file) throws IOException {
        byte[] buf = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        return buf;
    }
}
