package com.zwm.gallery;

final class WorkCategory {
    static final String ALL = "all";
    static final String CONVERSION = "conversion";
    static final String TRAFFIC = "traffic";
    static final String UNCATEGORIZED = "uncategorized";

    private WorkCategory() {
    }

    static String fromPath(String value) {
        String text = value == null ? "" : value;
        if (text.contains("[转]") || text.contains("【转】")) return CONVERSION;
        if (text.contains("[泛]") || text.contains("【泛】")) return TRAFFIC;
        return UNCATEGORIZED;
    }
}
