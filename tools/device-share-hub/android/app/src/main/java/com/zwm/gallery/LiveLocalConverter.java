package com.zwm.gallery;

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
 * Primary on-device hardware GPU live-photo engine for both Phone Gallery and Work Libraries.
 */
public final class LiveLocalConverter {
    private static final int MAX_DECODE_DIM = 3072;

    private LiveLocalConverter() {
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

    public static Uri convertUriOrPassthrough(
            Context context,
            Uri sourceUri,
            String modeId,
            float ratio,
            float durationSec,
            int index,
            boolean skipExistingLive
    ) throws IOException {
        byte[] srcBytes = readAllBytes(context.getContentResolver(), sourceUri);
        if (skipExistingLive && LiveMotionPhotoPacker.isMotionPhoto(srcBytes)) {
            return sourceUri;
        }
        byte[] motionPhoto = encodeMotionPhotoBytes(context, srcBytes, modeId, ratio, durationSec);
        return saveMotionPhotoToGallery(context, motionPhoto, buildOutputName(index));
    }

    public static File convertFileToFile(
            Context context,
            File sourceFile,
            File targetFile,
            String modeId,
            float ratio,
            float durationSec,
            boolean skipExistingLive
    ) throws IOException {
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new IOException("源图片文件不存在");
        }
        if (targetFile.isFile() && targetFile.length() > 0 && targetFile.lastModified() >= sourceFile.lastModified()) {
            return targetFile;
        }
        byte[] srcBytes = readFileBytes(sourceFile);
        if (skipExistingLive && LiveMotionPhotoPacker.isMotionPhoto(srcBytes)) {
            return sourceFile;
        }
        byte[] motionPhoto = encodeMotionPhotoBytes(context, srcBytes, modeId, ratio, durationSec);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(targetFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(motionPhoto);
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
        return targetFile;
    }

    private static byte[] encodeMotionPhotoBytes(
            Context context,
            byte[] srcBytes,
            String modeId,
            float ratio,
            float durationSec
    ) throws IOException {
        int orientation = LiveExifOrientation.readJpegOrientation(srcBytes);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.length, bounds);
        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDim / sample > MAX_DECODE_DIM) {
            sample *= 2;
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
            if (!upright.compress(Bitmap.CompressFormat.JPEG, 94, jpgOut)) {
                throw new IOException("JPEG 编码失败");
            }
            byte[] jpegCover = jpgOut.toByteArray();
            byte[] mp4Bytes = LiveGlVideoEncoder.encode(
                    upright,
                    context.getCacheDir(),
                    modeId,
                    ratio,
                    durationSec,
                    LiveMotionPreset.DEFAULT_FPS
            );
            return LiveMotionPhotoPacker.pack(jpegCover, mp4Bytes);
        } finally {
            if (!upright.isRecycled()) {
                upright.recycle();
            }
        }
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
