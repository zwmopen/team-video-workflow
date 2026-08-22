package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small protocol primitives shared by the future authenticated relay client. */
final class RemoteProtocol {
    private RemoteProtocol() { }

    static String canonicalJson(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) result.append(',');
                String key = keys.get(index);
                result.append(JSONObject.quote(key)).append(':')
                        .append(canonicalJson(object.get(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) result.append(',');
                result.append(canonicalJson(array.get(index)));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        if (value instanceof Boolean || value instanceof Number) {
            if (value instanceof Double && !Double.isFinite((Double) value)) {
                throw new JSONException("non-finite canonical JSON number");
            }
            if (value instanceof Float && !Float.isFinite((Float) value)) {
                throw new JSONException("non-finite canonical JSON number");
            }
            return String.valueOf(value);
        }
        throw new JSONException("unsupported canonical JSON value");
    }

    static byte[] canonicalBytes(Object value) throws JSONException {
        return canonicalJson(value).getBytes(StandardCharsets.UTF_8);
    }

    static String base64Url(byte[] value) {
        // java.util.Base64 is available from minSdk 26 and also keeps JVM unit
        // tests independent from Android's unmocked platform stubs.
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static JSONObject publicJwk(ECPublicKey key) throws JSONException {
        return new JSONObject()
                .put("kty", "EC")
                .put("crv", "P-256")
                .put("x", base64Url(fixed32(key.getW().getAffineX())))
                .put("y", base64Url(fixed32(key.getW().getAffineY())));
    }

    static byte[] fixed32(BigInteger value) {
        byte[] source = value.toByteArray();
        byte[] result = new byte[32];
        int sourceOffset = Math.max(0, source.length - result.length);
        int copyLength = Math.min(source.length, result.length);
        System.arraycopy(source, sourceOffset, result, result.length - copyLength, copyLength);
        return result;
    }

    /** Android's ECDSA signer returns ASN.1 DER; V1 transports raw r||s. */
    static byte[] derSignatureToRaw(byte[] der) throws JSONException {
        if (der == null || der.length < 8 || (der[0] & 0xff) != 0x30) {
            throw new JSONException("invalid ECDSA signature");
        }
        int[] cursor = {1};
        int sequenceLength = derLength(der, cursor);
        if (sequenceLength != der.length - cursor[0]) throw new JSONException("invalid ECDSA length");
        byte[] r = derInteger(der, cursor);
        byte[] s = derInteger(der, cursor);
        if (cursor[0] != der.length) throw new JSONException("trailing ECDSA data");
        byte[] result = new byte[64];
        copyInteger(r, result, 0);
        copyInteger(s, result, 32);
        return result;
    }

    private static int derLength(byte[] der, int[] cursor) throws JSONException {
        if (cursor[0] >= der.length) throw new JSONException("missing DER length");
        int first = der[cursor[0]++] & 0xff;
        if ((first & 0x80) == 0) return first;
        int count = first & 0x7f;
        if (count < 1 || count > 2 || cursor[0] + count > der.length) {
            throw new JSONException("invalid DER length");
        }
        int result = 0;
        for (int index = 0; index < count; index++) result = (result << 8) | (der[cursor[0]++] & 0xff);
        return result;
    }

    private static byte[] derInteger(byte[] der, int[] cursor) throws JSONException {
        if (cursor[0] >= der.length || (der[cursor[0]++] & 0xff) != 0x02) {
            throw new JSONException("missing DER integer");
        }
        int length = derLength(der, cursor);
        if (length < 1 || cursor[0] + length > der.length) throw new JSONException("invalid DER integer");
        byte[] result = java.util.Arrays.copyOfRange(der, cursor[0], cursor[0] + length);
        cursor[0] += length;
        return result;
    }

    private static void copyInteger(byte[] source, byte[] target, int offset) throws JSONException {
        int sourceOffset = 0;
        while (sourceOffset < source.length - 1 && source[sourceOffset] == 0) sourceOffset++;
        int length = source.length - sourceOffset;
        if (length > 32) throw new JSONException("ECDSA integer is too large");
        System.arraycopy(source, sourceOffset, target, offset + 32 - length, length);
    }
}
