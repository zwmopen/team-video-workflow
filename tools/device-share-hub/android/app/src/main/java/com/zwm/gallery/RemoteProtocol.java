package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Canonical JSON serialization, SHA-256 hashing, and ES256 (ECDSA P-256 + SHA-256 in IEEE P1363 64-byte format)
 * matching Cloudflare WebCrypto verification on zwm-device-share-relay.
 */
public final class RemoteProtocol {
    private static final char[] B64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private RemoteProtocol() {}

    public static String canonicalize(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonicalize(arr.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String key : keys) {
                Object v = obj.opt(key);
                if (v == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(JSONObject.quote(key)).append(':').append(canonicalize(v));
            }
            sb.append('}');
            return sb.toString();
        }
        return JSONObject.quote(String.valueOf(value));
    }

    public static byte[] canonicalBytes(Object value) throws Exception {
        return canonicalize(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String signEs256P1363(PrivateKey privateKey, Object payload) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(canonicalBytes(payload));
        byte[] der = sig.sign();
        byte[] p1363 = derToP1363(der, 32);
        return b64UrlEncode(p1363);
    }

    public static boolean verifyEs256P1363(String xB64, String yB64, Object payload, String p1363B64) {
        try {
            BigInteger x = new BigInteger(1, b64UrlDecode(xB64));
            BigInteger y = new BigInteger(1, b64UrlDecode(yB64));
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
            PublicKey pub = KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
            byte[] der = p1363ToDer(b64UrlDecode(p1363B64));
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(pub);
            sig.update(canonicalBytes(payload));
            return sig.verify(der);
        } catch (Exception e) {
            return false;
        }
    }

    /** Converts ASN.1 DER ECDSA signature (SEQUENCE { INTEGER r, INTEGER s }) to raw P1363 (r || s). */
    public static byte[] derToP1363(byte[] der, int coordLen) throws Exception {
        if (der == null || der.length < 8 || der[0] != 0x30) {
            throw new IllegalArgumentException("Invalid ECDSA DER signature");
        }
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            int lenBytes = der[1] & 0x7F;
            offset = 2 + lenBytes;
        }
        if (der[offset] != 0x02) throw new IllegalArgumentException("Expected INTEGER r");
        int rLen = der[offset + 1] & 0xFF;
        int rStart = offset + 2;
        int sTag = rStart + rLen;
        if (der[sTag] != 0x02) throw new IllegalArgumentException("Expected INTEGER s");
        int sLen = der[sTag + 1] & 0xFF;
        int sStart = sTag + 2;

        byte[] out = new byte[coordLen * 2];
        copyIntComponent(der, rStart, rLen, out, 0, coordLen);
        copyIntComponent(der, sStart, sLen, out, coordLen, coordLen);
        return out;
    }

    public static byte[] p1363ToDer(byte[] raw) {
        int half = raw.length / 2;
        byte[] r = trimLeadingZeroPositive(Arrays.copyOfRange(raw, 0, half));
        byte[] s = trimLeadingZeroPositive(Arrays.copyOfRange(raw, half, raw.length));
        int seqLen = 2 + r.length + 2 + s.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(seqLen + 4);
        out.write(0x30);
        if (seqLen >= 128) {
            out.write(0x81);
        }
        out.write(seqLen);
        out.write(0x02);
        out.write(r.length);
        out.write(r, 0, r.length);
        out.write(0x02);
        out.write(s.length);
        out.write(s, 0, s.length);
        return out.toByteArray();
    }

    private static byte[] trimLeadingZeroPositive(byte[] val) {
        int i = 0;
        while (i < val.length - 1 && val[i] == 0) i++;
        byte[] trimmed = Arrays.copyOfRange(val, i, val.length);
        if ((trimmed[0] & 0x80) != 0) {
            byte[] padded = new byte[trimmed.length + 1];
            System.arraycopy(trimmed, 0, padded, 1, trimmed.length);
            return padded;
        }
        return trimmed;
    }

    private static void copyIntComponent(byte[] src, int srcOff, int srcLen,
                                         byte[] dst, int dstOff, int coordLen) {
        int sOff = srcOff;
        int sLen = srcLen;
        while (sLen > 0 && src[sOff] == 0) {
            sOff++;
            sLen--;
        }
        if (sLen > coordLen) {
            System.arraycopy(src, sOff + sLen - coordLen, dst, dstOff, coordLen);
        } else {
            System.arraycopy(src, sOff, dst, dstOff + (coordLen - sLen), sLen);
        }
    }

    public static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        return hex(digest);
    }

    public static String sha256FileHex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n > 0) md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    public static String hex(byte[] bytes) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hexChars[v >>> 4];
            out[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(out);
    }

    public static String b64UrlEncode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int has1 = i < data.length ? 1 : 0;
            int b1 = has1 == 1 ? (data[i++] & 0xFF) : 0;
            int has2 = i < data.length ? 1 : 0;
            int b2 = has2 == 1 ? (data[i++] & 0xFF) : 0;
            sb.append(B64_ALPHABET[b0 >>> 2]);
            sb.append(B64_ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            if (has1 == 1) sb.append(B64_ALPHABET[((b1 & 0x0F) << 2) | (b2 >>> 6)]);
            if (has2 == 1) sb.append(B64_ALPHABET[b2 & 0x3F]);
        }
        return sb.toString();
    }

    public static byte[] b64UrlDecode(String str) {
        if (str == null || str.isEmpty()) return new byte[0];
        String s = str.replace('-', '+').replace('_', '/').replace("=", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream((s.length() * 3) / 4);
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int val = -1;
            if (c >= 'A' && c <= 'Z') val = c - 'A';
            else if (c >= 'a' && c <= 'z') val = c - 'a' + 26;
            else if (c >= '0' && c <= '9') val = c - '0' + 52;
            else if (c == '+') val = 62;
            else if (c == '/') val = 63;
            if (val < 0) continue;
            buf = (buf << 6) | val;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
