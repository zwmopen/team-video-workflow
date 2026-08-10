package com.zwm.gallery;

import java.util.List;

final class WorkInventoryCounts {
    final int total;
    final int conversion;
    final int traffic;
    final int uncategorized;

    private WorkInventoryCounts(int total, int conversion, int traffic, int uncategorized) {
        this.total = total;
        this.conversion = conversion;
        this.traffic = traffic;
        this.uncategorized = uncategorized;
    }

    static WorkInventoryCounts fromEntries(List<WorkLibrary.WorkEntry> entries) {
        java.util.ArrayList<String> categories = new java.util.ArrayList<>();
        if (entries != null) {
            for (WorkLibrary.WorkEntry entry : entries) {
                categories.add(entry == null ? WorkCategory.UNCATEGORIZED : entry.category);
            }
        }
        return fromCategories(categories);
    }

    static WorkInventoryCounts fromCategories(List<String> categories) {
        int total = 0;
        int conversion = 0;
        int traffic = 0;
        int uncategorized = 0;
        if (categories != null) {
            for (String category : categories) {
                total += 1;
                if (WorkCategory.CONVERSION.equals(category)) conversion += 1;
                else if (WorkCategory.TRAFFIC.equals(category)) traffic += 1;
                else uncategorized += 1;
            }
        }
        return new WorkInventoryCounts(total, conversion, traffic, uncategorized);
    }
}
