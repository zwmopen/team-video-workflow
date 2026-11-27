package com.zwm.gallery;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

/**
 * Generates and persists an EC P-256 device identity privately inside app-private filesDir.
 * Private keys never leave the device; only the JWK public coordinates are shared on LAN enroll.
 */
public final class RemoteIdentity {
    private static final String FILE_NAME = "remote_identity_v1.json";

    public final PrivateKey signingPrivateKey;
    public final JSONObject signingPublicJwk;
    public final String x;
    public final String y;

    private RemoteIdentity(PrivateKey signingPrivateKey, String x, String y) throws Exception {
        this.signingPrivateKey = signingPrivateKey;
        this.x = x;
        this.y = y;
        JSONObject jwk = new JSONObject();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", x);
        jwk.put("y", y);
        this.signingPublicJwk = jwk;
    }

    public static synchronized RemoteIdentity loadOrCreate(Context context) throws Exception {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.isFile()) {
            try {
                String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(raw);
                byte[] pkcs8 = RemoteProtocol.b64UrlDecode(obj.getString("privatePkcs8"));
                KeyFactory kf = KeyFactory.getInstance("EC");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                String x = obj.getString("x");
                String y = obj.getString("y");
                if (!x.isEmpty() && !y.isEmpty()) {
                    return new RemoteIdentity(priv, x, y);
                }
            } catch (Exception ignored) {
                // Regenerate cleanly if corrupted
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = kpg.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        String x = RemoteProtocol.b64UrlEncode(toFixed32(pub.getW().getAffineX()));
        String y = RemoteProtocol.b64UrlEncode(toFixed32(pub.getW().getAffineY()));
        JSONObject saved = new JSONObject();
        saved.put("version", 1);
        saved.put("privatePkcs8", RemoteProtocol.b64UrlEncode(pair.getPrivate().getEncoded()));
        saved.put("x", x);
        saved.put("y", y);
        File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        Files.write(tmp.toPath(), saved.toString().getBytes(StandardCharsets.UTF_8));
        if (file.exists()) file.delete();
        tmp.renameTo(file);
        return new RemoteIdentity(pair.getPrivate(), x, y);
    }

    public static byte[] toFixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) return raw;
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    public static byte[] fromFixedUnsigned(String b64) {
        return RemoteProtocol.b64UrlDecode(b64);
    }

    static byte[] stripLeadingZeros(byte[] src) {
        int i = 0;
        while (i < src.length - 1 && src[i] == 0) i++;
        return Arrays.copyOfRange(src, i, src.length);
    }
}
