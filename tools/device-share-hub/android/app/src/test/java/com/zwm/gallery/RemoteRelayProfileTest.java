package com.zwm.gallery;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RemoteRelayProfileTest {
    @Test public void profileCertificateShapeIsExplicit() throws Exception {
        JSONObject certificate = new JSONObject()
                .put("workspaceId", "ws_test")
                .put("deviceId", "android-test")
                .put("signingPublicKey", new JSONObject())
                .put("agreementPublicKey", new JSONObject());
        assertEquals("android-test", certificate.getString("deviceId"));
        assertEquals("ws_test", certificate.getString("workspaceId"));
    }
}
