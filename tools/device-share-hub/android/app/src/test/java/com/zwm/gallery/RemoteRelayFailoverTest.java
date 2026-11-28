package com.zwm.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class RemoteRelayFailoverTest {
    private static final String PRIMARY = "https://primary.example";
    private static final String BACKUP = "https://backup.example";

    @Test public void timeoutStartsNewAuthenticationAtBackup() throws Exception {
        List<String> calls = new ArrayList<>();
        RemoteRelayClient.Session received = RemoteRelayClient.authenticateWithFallback(PRIMARY, BACKUP, endpoint -> {
            calls.add(endpoint);
            if (PRIMARY.equals(endpoint)) throw new SocketTimeoutException();
            return new RemoteRelayClient.Session(endpoint, "workspace", "device", "backup-token", 0);
        });
        assertEquals(Arrays.asList(PRIMARY, BACKUP), calls);
        assertEquals(BACKUP, received.endpoint);
    }

    @Test public void authorizationFailureDoesNotContactBackup() throws Exception {
        List<String> calls = new ArrayList<>();
        Exception rejected = new RemoteRelayClient.HttpStatusException(403, "revoked");
        try {
            RemoteRelayClient.authenticateWithFallback(PRIMARY, BACKUP, endpoint -> {
                calls.add(endpoint);
                throw rejected;
            });
            fail("Expected permission error");
        } catch (Exception result) {
            assertSame(rejected, result);
        }
        assertEquals(Collections.singletonList(PRIMARY), calls);
    }

    @Test public void alteredBackupAuthorityIsIgnored() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair key = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) key.getPublic();
        JSONObject issuer = new JSONObject().put("kty", "EC").put("crv", "P-256")
                .put("x", RemoteProtocol.b64UrlEncode(coordinate(publicKey.getW().getAffineX())))
                .put("y", RemoteProtocol.b64UrlEncode(coordinate(publicKey.getW().getAffineY())));
        String workspace = "ws_" + RemoteProtocol.sha256Hex(RemoteProtocol.canonicalBytes(issuer)).substring(0, 32);
        JSONObject routes = new JSONObject().put("workspaceId", workspace)
                .put("primaryAuthority", "primary.example").put("backupAuthority", "backup.example");
        JSONObject profile = new JSONObject().put("relayRoutes", routes).put("relayRoutesIssuerKey", issuer)
                .put("relayRoutesSignature", RemoteProtocol.signEs256P1363(key.getPrivate(), routes));
        assertEquals(BACKUP, RemoteRelayProfile.backupEndpoint(profile, PRIMARY, workspace));
        assertEquals("", RemoteRelayProfile.backupEndpoint(profile, PRIMARY, "ws_wrong"));
        routes.put("backupAuthority", "modified.example");
        assertEquals("", RemoteRelayProfile.backupEndpoint(profile, PRIMARY, workspace));
    }

    private static byte[] coordinate(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] result = new byte[32];
        int count = Math.min(bytes.length, result.length);
        System.arraycopy(bytes, bytes.length - count, result, result.length - count, count);
        return result;
    }
}
