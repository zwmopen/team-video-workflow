package com.zwm.livecore;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unified lossless-static / quality-tiered Live Photo converter shared by both
 * 团队工作相册 and 独立实况工坊 on Android.
 *
 * Key architecture:
 * 1. For JPEG inputs: static cover keeps 100% original compressed JPEG scan data, resolution,
 *    EXIF orientation, and ICC color profile via XMP injection without re-encoding.
 * 2. Dynamic video decodes/renders in an independent pipeline at the chosen LiveQualityTier
 *    (1080P / 1440P / 2048P) with hardware encoder fallback.
 */
public final class LiveMotionPhotoConverter {
    private static final int NON_JPEG_OOM_GUARD_MAX_DIM = 8192;

    private LiveMotionPhotoConverter() {
    }

    public static boolean isAlreadyMotionPhoto(ContentResolver resolver, Uri uri) {
        if (resolver == null || uri == null) return false;
        try {
            byte[] data = readAllBytes(resolver, uri);
            return LiveMotionPhotoPacker.isMotionPhoto(data);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isAlreadyMotionPhoto(File file) {
        if (file == null || !file.isFile()) return false;
        try {
            byte[] data = readFileBytes(file);
            return LiveMotionPhotoPacker.isMotionPhoto(data);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static LiveConvertResult convertUriDetailed(
            Context context,
            Uri sourceUri,
            String modeId,
            LiveQualityTier qualityTier,
            float ratio,
            float durationSec,
            int index,
            boolean skipExistingLive
    ) throws IOException {
        long t0 = System.currentTimeMillis();
        byte[] srcBytes = readAllBytes(context.getContentResolver(), sourceUri);
        LiveQualityTier safeTier = qualityTier != null ? qualityTier : LiveQualityTier.HD;

        if (skipExistingLive && LiveMotionPhotoPacker.isMotionPhoto(srcBytes)) {
            int[] dims = probeDimensions(srcBytes);
            return new LiveConvertResult(
                    sourceUri,
                    null,
                    LiveConvertResult.CoverMode.PASSTHROUGH_EXISTING_LIVE,
                    "already_motion_photo",
                    dims[0],
                    dims[1],
                    srcBytes.length,
                    0,
                    0,
                    0,
                    0,
                    safeTier,
                    safeTier,
                    null,
                    System.currentTimeMillis() - t0
            );
        }

        EncodedPackage pkg = encodeMotionPhotoPackage(context, srcBytes, modeId, safeTier, ratio, durationSec);
        Uri outUri = saveMotionPhotoToGallery(context, pkg.motionPhotoBytes, buildOutputName(index));
        return new LiveConvertResult(
                outUri,
                null,
                pkg.coverMode,
                pkg.reencodeReason,
                pkg.coverWidth,
                pkg.coverHeight,
                pkg.coverBytes,
                pkg.videoResult.width,
                pkg.videoResult.height,
                pkg.videoResult.bitrateBps,
                pkg.videoResult.mp4Bytes.length,
                safeTier,
                pkg.videoResult.effectiveTier,
                pkg.videoResult.downgradeReason,
                System.currentTimeMillis() - t0
        );
    }

    public static LiveConvertResult convertFileToFileDetailed(
            Context context,
            File sourceFile,
            File targetFile,
            String modeId,
            LiveQualityTier qualityTier,
            float ratio,
            float durationSec,
            boolean skipExistingLive
    ) throws IOException {
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new IOException("源图片文件不存在");
        }
        long t0 = System.currentTimeMillis();
        LiveQualityTier safeTier = qualityTier != null ? qualityTier : LiveQualityTier.HD;
        if (targetFile.isFile() && targetFile.length() > 0 && targetFile.lastModified() >= sourceFile.lastModified()) {
            byte[] existing = readFileBytes(targetFile);
            int[] dims = probeDimensions(existing);
            return new LiveConvertResult(
                    Uri.fromFile(targetFile),
                    targetFile,
                    LiveConvertResult.CoverMode.PASSTHROUGH_EXISTING_LIVE,
                    "cached_target_reused",
                    dims[0],
                    dims[1],
                    existing.length,
                    0,
                    0,
                    0,
                    0,
                    safeTier,
                    safeTier,
                    null,
                    System.currentTimeMillis() - t0
            );
        }
        byte[] srcBytes = readFileBytes(sourceFile);
        if (skipExistingLive && LiveMotionPhotoPacker.isMotionPhoto(srcBytes)) {
            int[] dims = probeDimensions(srcBytes);
            return new LiveConvertResult(
                    Uri.fromFile(sourceFile),
                    sourceFile,
                    LiveConvertResult.CoverMode.PASSTHROUGH_EXISTING_LIVE,
                    "already_motion_photo",
                    dims[0],
                    dims[1],
                    srcBytes.length,
                    0,
                    0,
                    0,
                    0,
                    safeTier,
                    safeTier,
                    null,
                    System.currentTimeMillis() - t0
            );
        }
        EncodedPackage pkg = encodeMotionPhotoPackage(context, srcBytes, modeId, safeTier, ratio, durationSec);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(targetFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(pkg.motionPhotoBytes);
            fos.flush();
        }
        if (targetFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.delete();
        }
        if (!tmp.renameTo(targetFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("写入实况缓存失败：" + targetFile.getName());
        }
        return new LiveConvertResult(
                Uri.fromFile(targetFile),
                targetFile,
                pkg.coverMode,
                pkg.reencodeReason,
                pkg.coverWidth,
                pkg.coverHeight,
                pkg.coverBytes,
                pkg.videoResult.width,
                pkg.videoResult.height,
                pkg.videoResult.bitrateBps,
                pkg.videoResult.mp4Bytes.length,
                safeTier,
                pkg.videoResult.effectiveTier,
                pkg.videoResult.downgradeReason,
                System.currentTimeMillis() - t0
        );
    }

    private static final class EncodedPackage {
        final byte[] motionPhotoBytes;
        final LiveConvertResult.CoverMode coverMode;
        final String reencodeReason;
        final int coverWidth;
        final int coverHeight;
        final int coverBytes;
        final LiveGlVideoEncoder.EncodedVideoResult videoResult;

        EncodedPackage(
                byte[] motionPhotoBytes,
                LiveConvertResult.CoverMode coverMode,
                String reencodeReason,
                int coverWidth,
                int coverHeight,
                int coverBytes,
                LiveGlVideoEncoder.EncodedVideoResult videoResult
        ) {
            this.motionPhotoBytes = motionPhotoBytes;
            this.coverMode = coverMode;
            this.reencodeReason = reencodeReason;
            this.coverWidth = coverWidth;
            this.coverHeight = coverHeight;
            this.coverBytes = coverBytes;
            this.videoResult = videoResult;
        }
    }

    private static EncodedPackage encodeMotionPhotoPackage(
            Context context,
            byte[] srcBytes,
            String modeId,
            LiveQualityTier tier,
            float ratio,
            float durationSec
    ) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.length, bounds);
        int origW = Math.max(0, bounds.outWidth);
        int origH = Math.max(0, bounds.outHeight);
        int origMax = Math.max(origW, origH);

        boolean sourceIsJpeg = LiveMotionPhotoPacker.isJpeg(srcBytes);
        int orientation = sourceIsJpeg ? LiveExifOrientation.readJpegOrientation(srcBytes) : 1;

        if (sourceIsJpeg) {
            // Decoupled lossless JPEG path:
            // Static cover uses original untouched srcBytes; only decode a video-sized bitmap for MP4.
            int videoDecodeTarget = Math.max(tier.targetLongSide * 2, 2048);
            int sample = 1;
            while (origMax > 0 && (origMax / sample) > videoDecodeTarget) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap rawBmp = BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.length, opts);
            if (rawBmp == null) {
                throw new IOException("无法解码 JPEG 视频采样");
            }
            Bitmap upright = LiveExifOrientation.applyOrientation(rawBmp, orientation);
            try {
                LiveGlVideoEncoder.EncodedVideoResult videoRes = LiveGlVideoEncoder.encodeWithFallback(
                        upright,
                        context.getCacheDir(),
                        modeId,
                        tier,
                        ratio,
                        durationSec,
                        tier.fps
                );
                byte[] packed = LiveMotionPhotoPacker.pack(srcBytes, videoRes.mp4Bytes);
                return new EncodedPackage(
                        packed,
                        LiveConvertResult.CoverMode.LOSSLESS_JPEG_PASSTHROUGH,
                        null,
                        origW,
                        origH,
                        srcBytes.length,
                        videoRes
                );
            } finally {
                if (!upright.isRecycled()) upright.recycle();
            }
        }

        // Non-JPEG (PNG / WebP / HEIC): decode at full resolution (only guarding extreme >8192px against OOM)
        int sample = 1;
        String reencodeReason = "non_jpeg_source_transcoded_to_jpeg";
        while (origMax > 0 && (origMax / sample) > NON_JPEG_OOM_GUARD_MAX_DIM) {
            sample *= 2;
            reencodeReason = "non_jpeg_source_over_8192_sampled";
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap rawBmp = BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.length, opts);
        if (rawBmp == null) {
            throw new IOException("无法解码图片内容");
        }
        Bitmap upright = LiveExifOrientation.applyOrientation(rawBmp, orientation);
        try {
            ByteArrayOutputStream jpgOut = new ByteArrayOutputStream(Math.max(64 * 1024, srcBytes.length));
            if (!upright.compress(Bitmap.CompressFormat.JPEG, 98, jpgOut)) {
                throw new IOException("JPEG 编码失败");
            }
            byte[] jpegCover = jpgOut.toByteArray();
            LiveGlVideoEncoder.EncodedVideoResult videoRes = LiveGlVideoEncoder.encodeWithFallback(
                    upright,
                    context.getCacheDir(),
                    modeId,
                    tier,
                    ratio,
                    durationSec,
                    tier.fps
            );
            byte[] packed = LiveMotionPhotoPacker.pack(jpegCover, videoRes.mp4Bytes);
            return new EncodedPackage(
                    packed,
                    LiveConvertResult.CoverMode.TRANSCODED_NON_JPEG,
                    reencodeReason,
                    upright.getWidth(),
                    upright.getHeight(),
                    jpegCover.length,
                    videoRes
            );
        } finally {
            if (!upright.isRecycled()) upright.recycle();
        }
    }

    private static int[] probeDimensions(byte[] raw) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        return new int[]{Math.max(0, bounds.outWidth), Math.max(0, bounds.outHeight)};
    }

    private static String buildOutputName(int index) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return String.format(Locale.US, "MVIMG_%s_%02d.jpg", stamp, Math.max(1, index));
    }

    private static Uri saveMotionPhotoToGallery(Context context, byte[] data, String fileName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/实况工坊");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("系统相册拒绝写入");
            }
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法打开相册输出流");
                    out.write(data);
                    out.flush();
                }
                ContentValues clear = new ContentValues();
                clear.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, clear, null, null);
                return uri;
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException(e);
            }
        }

        File pubDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "实况工坊");
        if (!pubDir.exists() && !pubDir.mkdirs()) {
            throw new IOException("无法创建实况工坊相册目录");
        }
        File target = new File(pubDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(data);
            fos.flush();
        }
        final Uri[] scanned = {null};
        CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(
                context.getApplicationContext(),
                new String[]{target.getAbsolutePath()},
                new String[]{"image/jpeg"},
                (path, uri) -> {
                    scanned[0] = uri;
                    latch.countDown();
                }
        );
        try {
            latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return scanned[0] != null ? scanned[0] : Uri.fromFile(target);
    }

    private static byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, file.length()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static byte[] readAllBytes(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) throw new IOException("无法读取所选图片");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
