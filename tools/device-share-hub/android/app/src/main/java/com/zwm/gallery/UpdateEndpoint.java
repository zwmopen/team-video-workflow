package com.zwm.gallery;

final class UpdateEndpoint {
    static final String RELEASE_PAGE =
            "https://github.com/zwmopen/gallery-updates/releases";
    static final String RELEASE_MANIFEST =
            "https://raw.githubusercontent.com/zwmopen/gallery-updates/main/latest.json";
    static final String RELEASE_API =
            "https://api.github.com/repos/zwmopen/gallery-updates/releases/latest";

    private UpdateEndpoint() {
    }
}
