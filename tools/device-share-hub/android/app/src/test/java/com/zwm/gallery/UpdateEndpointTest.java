package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class UpdateEndpointTest {
    @Test
    public void usesPublicUpdateRepository() {
        assertEquals(
                "https://api.github.com/repos/zwmopen/gallery-updates/releases/latest",
                UpdateEndpoint.RELEASE_API);
    }
}
