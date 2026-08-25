package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class UpdateEndpointTest {
    @Test
    public void usesPublicUpdateRepository() {
        assertEquals(
                "https://github.com/zwmopen/gallery-updates/releases",
                UpdateEndpoint.RELEASE_PAGE);
        assertEquals(
                "https://raw.githubusercontent.com/zwmopen/gallery-updates/main/latest.json",
                UpdateEndpoint.RELEASE_MANIFEST);
        assertEquals(
                "https://raw.githubusercontent.com/zwmopen/gallery-updates/main/latest.json",
                UpdateEndpoint.RELEASE_MANIFEST);
        assertEquals(
                "https://api.github.com/repos/zwmopen/gallery-updates/releases/latest",
                UpdateEndpoint.RELEASE_API);
    }
}
