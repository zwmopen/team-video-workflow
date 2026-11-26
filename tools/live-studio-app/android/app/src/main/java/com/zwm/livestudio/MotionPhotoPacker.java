package com.zwm.livestudio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java Google Camera Motion Photo V1/V2 XMP injector & validator.
 */
public final class MotionPhotoPacker {
    private static final byte[] XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern XMP_MP_RE = Pattern.compile("(?:Camera|GCamera):(?:MotionPhoto|MicroVideo)\\s*=\\s*[\"']1[\"']");
    private static final Pattern XMP_OFFSET_RE = Pattern.compile("(?:GCamera:MicroVideoOffset|Item:Length)\\s*=\\s*[\"'](\\d+)[\"']");

    private MotionPhotoPacker() {
    }

    public static boolean isMotionPhoto(byte[] raw) {
        if (raw == null || raw.length < 32 || (raw[0] & 0xFF) != 0xFF || (raw[1] & 0xFF) != 0xD8) {
            return false;
        }
        int headLen = Math.min(raw.length, 131072);
        int xmpPos = indexOf(raw, XMP_HEADER, 0, headLen);
        if (xmpPos < 0) return false;
        String headText = new String(raw, xmpPos, headLen - xmpPos, StandardCharsets.ISO_8859_1);
        if (!XMP_MP_RE.matcher(headText).find()) return false;
        Matcher m = XMP_OFFSET_RE.matcher(headText);
        while (m.find()) {
            try {
                int offset = Integer.parseInt(m.group(1));
                if (offset >= 16 && offset < raw.length - 20) {
                    int mp4Start = raw.length - offset;
                    if (raw[mp4Start + 4] == 'f' && raw[mp4Start + 5] == 't'
                            && raw[mp4Start + 6] == 'y' && raw[mp4Start + 7] == 'p') {
                        return true;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    public static byte[] pack(byte[] jpegBytes, byte[] mp4Bytes) {
        if (jpegBytes == null || jpegBytes.length < 4
                || (jpegBytes[0] & 0xFF) != 0xFF || (jpegBytes[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("输入图片不是有效 JPEG");
        }
        if (mp4Bytes == null || mp4Bytes.length < 16) {
            throw new IllegalArgumentException("MP4 视频数据无效");
        }

        byte[] cleanJpeg = stripMotionTrailerAndXmp(jpegBytes);
        byte[] xmpPayload = buildXmpPayload(mp4Bytes.length);

        int segLen = xmpPayload.length + 2;
        if (segLen > 0xFFFF) {
            throw new IllegalStateException("XMP 超出 APP1 段大小限制");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(cleanJpeg.length + segLen + 2 + mp4Bytes.length);
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.write(xmpPayload, 0, xmpPayload.length);
        out.write(cleanJpeg, 2, cleanJpeg.length - 2);
        out.write(mp4Bytes, 0, mp4Bytes.length);
        return out.toByteArray();
    }

    private static byte[] stripMotionTrailerAndXmp(byte[] raw) {
        int endLimit = raw.length;
        // If input is already a Motion Photo, strip existing trailing MP4 via declared offset.
        if (isMotionPhoto(raw)) {
            int headLen = Math.min(raw.length, 131072);
            int xmpPos = indexOf(raw, XMP_HEADER, 0, headLen);
            if (xmpPos >= 0) {
                String headText = new String(raw, xmpPos, headLen - xmpPos, StandardCharsets.ISO_8859_1);
                Matcher m = XMP_OFFSET_RE.matcher(headText);
                while (m.find()) {
                    try {
                        int off = Integer.parseInt(m.group(1));
                        if (off >= 16 && off < raw.length - 20) {
                            endLimit = Math.min(endLimit, raw.length - off);
                            break;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(endLimit);
        out.write(0xFF);
        out.write(0xD8);
        int pos = 2;
        while (pos < endLimit) {
            if ((raw[pos] & 0xFF) != 0xFF) {
                out.write(raw, pos, endLimit - pos);
                break;
            }
            int ffStart = pos;
            while (pos < endLimit && (raw[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= endLimit) {
                out.write(raw, ffStart, endLimit - ffStart);
                break;
            }
            int marker = raw[pos] & 0xFF;
            pos++;
            if (marker == 0xDA) {
                out.write(raw, ffStart, endLimit - ffStart);
                break;
            }
            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                break;
            }
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(raw, ffStart, pos - ffStart);
                continue;
            }
            if (pos + 2 > endLimit) {
                out.write(raw, ffStart, endLimit - ffStart);
                break;
            }
            int segLen = ((raw[pos] & 0xFF) << 8) | (raw[pos + 1] & 0xFF);
            int segEnd = Math.min(endLimit, pos + segLen);
            boolean isOldMotionXmp = marker == 0xE1
                    && segEnd - (pos + 2) > XMP_HEADER.length
                    && startsWithAt(raw, pos + 2, XMP_HEADER)
                    && containsMotionTag(raw, pos + 2, segEnd);
            if (!isOldMotionXmp) {
                out.write(raw, ffStart, segEnd - ffStart);
            }
            pos = segEnd;
        }
        return out.toByteArray();
    }

    private static boolean containsMotionTag(byte[] buf, int start, int end) {
        String s = new String(buf, start, end - start, StandardCharsets.ISO_8859_1);
        return s.contains("MotionPhoto") || s.contains("MicroVideo");
    }

    private static byte[] buildXmpPayload(int mp4Length) {
        String xml = "<?xpacket begin=\"\ufeff\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\" "
                + "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" "
                + "xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" "
                + "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" "
                + "Camera:MotionPhoto=\"1\" "
                + "Camera:MotionPhotoVersion=\"1\" "
                + "Camera:MotionPhotoPresentationTimestampUs=\"0\" "
                + "GCamera:MotionPhoto=\"1\" "
                + "GCamera:MotionPhotoVersion=\"1\" "
                + "GCamera:MotionPhotoPresentationTimestampUs=\"0\" "
                + "GCamera:MicroVideo=\"1\" "
                + "GCamera:MicroVideoVersion=\"1\" "
                + "GCamera:MicroVideoPresentationTimestampUs=\"0\" "
                + "GCamera:MicroVideoOffset=\"" + mp4Length + "\">"
                + "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\">"
                + "<Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>"
                + "</rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\">"
                + "<Container:Item Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\" Item:Length=\"" + mp4Length + "\"/>"
                + "</rdf:li>"
                + "</rdf:Seq></Container:Directory>"
                + "</rdf:Description>"
                + "</rdf:RDF>"
                + "</x:xmpmeta>"
                + "<?xpacket end=\"w\"?>";
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[XMP_HEADER.length + xmlBytes.length];
        System.arraycopy(XMP_HEADER, 0, out, 0, XMP_HEADER.length);
        System.arraycopy(xmlBytes, 0, out, XMP_HEADER.length, xmlBytes.length);
        return out;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int limit) {
        int max = limit - needle.length;
        for (int i = from; i <= max; i++) {
            if (startsWithAt(haystack, i, needle)) return i;
        }
        return -1;
    }

    private static boolean startsWithAt(byte[] buf, int offset, byte[] pat) {
        if (offset < 0 || offset + pat.length > buf.length) return false;
        for (int i = 0; i < pat.length; i++) {
            if (buf[offset + i] != pat[i]) return false;
        }
        return true;
    }
}
