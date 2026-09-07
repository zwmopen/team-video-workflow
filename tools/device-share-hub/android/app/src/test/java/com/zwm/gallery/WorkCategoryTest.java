package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class WorkCategoryTest {
    @Test public void classifiesCollectionTags() {
        assertEquals(WorkCategory.CONVERSION, WorkCategory.fromPath("作品集_044[转]/笔记"));
        assertEquals(WorkCategory.TRAFFIC, WorkCategory.fromPath("作品集_020[泛]/笔记"));
        assertEquals(WorkCategory.UNCATEGORIZED, WorkCategory.fromPath("作品集_004/笔记"));
    }

    @Test public void formatsFolderLabelsWithAdaptiveTruncation() {
        assertEquals("全部", MainActivity.formatFolderLabel("全部"));
        assertEquals("全部", MainActivity.formatFolderLabel(null));
        assertEquals("全部", MainActivity.formatFolderLabel(""));
        assertEquals("全部", MainActivity.formatFolderLabel("all"));
        assertEquals("安吉站", MainActivity.formatFolderLabel("安吉站"));
        assertEquals("12345", MainActivity.formatFolderLabel("12345"));
        assertEquals("作品集_1...", MainActivity.formatFolderLabel("作品集_100"));
        assertEquals("超过五个字...", MainActivity.formatFolderLabel("超过五个字的内容"));
    }
}
