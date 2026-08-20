package com.zwm.gallery;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RemoteRelayClientTest {
    @Test public void onlyHttpsRelayEndpointsAreAccepted() throws Exception {
        assertEquals("https://relay.example", RemoteRelayClient.normalizeEndpoint("https://relay.example///"));
    }

    @Test public void localHttpAndEmptyEndpointsAreRejected() {
        assertRejected("");
        assertRejected("http://relay.example");
        assertRejected("relay.example");
        assertRejected("https://relay.example/v1");
        assertRejected("https://relay.example?workspace=one");
    }

    private void assertRejected(String endpoint) {
        try {
            RemoteRelayClient.normalizeEndpoint(endpoint);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTPS")
                    || expected.getMessage().contains("地址"));
            return;
        }
        throw new AssertionError("endpoint should be rejected: " + endpoint);
    }
}
