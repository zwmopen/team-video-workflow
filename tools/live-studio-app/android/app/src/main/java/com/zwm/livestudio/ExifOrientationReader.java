package com.zwm.livestudio;

import android.graphics.Bitmap;
import android.graphics.Matrix;

public final class ExifOrientationReader {
    private ExifOrientationReader() {
    }

    /**
     * Reads EXIF IFD0 Orientation (tag 0x0112) from a JPEG byte stream without AndroidX dependencies.
     * Returns 1 when no orientation tag is present or data is not EXIF JPEG.
     */
    public static int readJpegOrientation(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 12 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                pos++;
                continue;
            }
            while (pos < jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= jpeg.length) break;
            int marker = jpeg[pos] & 0xFF;
            pos++;
            if (marker == 0xD9 || marker == 0xDA) break;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
            if (pos + 2 > jpeg.length) break;
            int segLen = ((jpeg[pos] & 0xFF) << 8) | (jpeg[pos + 1] & 0xFF);
            if (segLen < 2 || pos + segLen > jpeg.length) break;

            if (marker == 0xE1 && segLen >= 16
                    && jpeg[pos + 2] == 'E' && jpeg[pos + 3] == 'x'
                    && jpeg[pos + 4] == 'i' && jpeg[pos + 5] == 'f'
                    && jpeg[pos + 6] == 0 && jpeg[pos + 7] == 0) {
                return parseTiffOrientation(jpeg, pos + 8, pos + segLen);
            }
            pos += segLen;
        }
        return 1;
    }

    private static int parseTiffOrientation(byte[] data, int tiffStart, int limit) {
        if (tiffStart + 8 > limit) return 1;
        boolean little;
        if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
            little = true;
        } else if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        int magic = readU16(data, tiffStart + 2, little);
        if (magic != 42) return 1;
        int ifdOffset = readI32(data, tiffStart + 4, little);
        int ifdPos = tiffStart + ifdOffset;
        if (ifdPos < tiffStart || ifdPos + 2 > limit) return 1;

        int entries = readU16(data, ifdPos, little);
        int cur = ifdPos + 2;
        for (int i = 0; i < entries && cur + 12 <= limit; i++, cur += 12) {
            int tag = readU16(data, cur, little);
            if (tag == 0x0112) {
                int orient = readU16(data, cur + 8, little);
                return (orient >= 1 && orient <= 8) ? orient : 1;
            }
        }
        return 1;
    }

    private static int readU16(byte[] b, int off, boolean little) {
        if (little) return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readI32(byte[] b, int off, boolean little) {
        if (little) {
            return (b[off] & 0xFF)
                    | ((b[off + 1] & 0xFF) << 8)
                    | ((b[off + 2] & 0xFF) << 16)
                    | ((b[off + 3] & 0xFF) << 24);
        }
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    public static Bitmap applyOrientation(Bitmap src, int orientation) {
        if (src == null || orientation <= 1 || orientation > 8) return src;
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1f, 1f); break;
            case 3: m.setRotate(180f); break;
            case 4: m.setScale(1f, -1f); break;
            case 5: m.setRotate(90f); m.postScale(-1f, 1f); break;
            case 6: m.setRotate(90f); break;
            case 7: m.setRotate(-90f); m.postScale(-1f, 1f); break;
            case 8: m.setRotate(-90f); break;
            default: return src;
        }
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (rotated != src) src.recycle();
        return rotated;
    }
}
