package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RemoteProtocolTest {
    @Test public void canonicalJsonSortsNestedObjectsButKeepsArrayOrder() throws Exception {
        JSONObject value = new JSONObject()
                .put("z", 1)
                .put("a", new JSONObject().put("b", true).put("a", "x"))
                .put("items", new JSONArray().put(2).put(1));
        assertEquals("{\"a\":{\"a\":\"x\",\"b\":true},\"items\":[2,1],\"z\":1}",
                RemoteProtocol.canonicalJson(value));
    }

    @Test public void publicJwkContainsOnlyThePublicP256Coordinates() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair pair = generator.generateKeyPair();
        JSONObject jwk = RemoteProtocol.publicJwk((java.security.interfaces.ECPublicKey) pair.getPublic());
        assertEquals("EC", jwk.getString("kty"));
        assertEquals("P-256", jwk.getString("crv"));
        assertEquals(43, jwk.getString("x").length());
        assertEquals(43, jwk.getString("y").length());
        assertFalse(jwk.has("d"));
    }

    @Test public void convertsDerEcdsaSignatureToTheProtocolRawForm() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair pair = generator.generateKeyPair();
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(new byte[]{1, 2, 3});
        byte[] raw = RemoteProtocol.derSignatureToRaw(signature.sign());
        assertEquals(64, raw.length);
        boolean nonZero = false;
        for (byte value : raw) nonZero |= value != 0;
        assertTrue(nonZero);
    }
}
