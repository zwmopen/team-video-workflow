package com.zwm.gallery;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RemoteProtocolTest {
    @Test
    public void canonicalizeSortsNestedKeys() throws Exception {
        JSONObject nested = new JSONObject();
        nested.put("z", 2);
        nested.put("a", 1);
        JSONObject root = new JSONObject();
        root.put("b", nested);
        root.put("a", "ok");
        assertEquals("{\"a\":\"ok\",\"b\":{\"a\":1,\"z\":2}}", RemoteProtocol.canonicalize(root));
    }

    @Test
    public void es256DerToP1363RoundtripMatchesWebCrypto() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = kpg.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        String x = RemoteProtocol.b64UrlEncode(RemoteIdentity.toFixed32(pub.getW().getAffineX()));
        String y = RemoteProtocol.b64UrlEncode(RemoteIdentity.toFixed32(pub.getW().getAffineY()));

        JSONObject challenge = new JSONObject();
        challenge.put("challengeId", "ch_12345678");
        challenge.put("nonce", "abcdef");
        challenge.put("version", 1);

        String sig = RemoteProtocol.signEs256P1363(pair.getPrivate(), challenge);
        byte[] decoded = RemoteProtocol.b64UrlDecode(sig);
        assertEquals(64, decoded.length);
        assertTrue(RemoteProtocol.verifyEs256P1363(x, y, challenge, sig));
    }

    @Test
    public void toFixed32HandlesLeadingZeroPadding() {
        byte[] padded = RemoteIdentity.toFixed32(BigInteger.valueOf(42));
        assertEquals(32, padded.length);
        assertEquals(42, padded[31]);
    }
}
