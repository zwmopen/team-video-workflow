package com.zwm.gallery;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class WorkInventoryCountsTest {
    @Test
    public void countsEveryPublishedCategoryWithoutReplacingTheTotal() {
        WorkInventoryCounts counts = WorkInventoryCounts.fromCategories(Arrays.asList(
                WorkCategory.CONVERSION,
                WorkCategory.CONVERSION,
                WorkCategory.TRAFFIC,
                WorkCategory.UNCATEGORIZED,
                "unexpected"
        ));

        assertEquals(5, counts.total);
        assertEquals(2, counts.conversion);
        assertEquals(1, counts.traffic);
        assertEquals(2, counts.uncategorized);
    }
}
