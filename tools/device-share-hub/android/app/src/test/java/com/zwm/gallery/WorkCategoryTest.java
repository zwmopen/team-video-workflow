package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class WorkCategoryTest {
    @Test public void classifiesCollectionTags() {
        assertEquals(WorkCategory.CONVERSION, WorkCategory.fromPath("作品集_044[转]/笔记"));
        assertEquals(WorkCategory.TRAFFIC, WorkCategory.fromPath("作品集_020[泛]/笔记"));
        assertEquals(WorkCategory.UNCATEGORIZED, WorkCategory.fromPath("作品集_004/笔记"));
    }
}
