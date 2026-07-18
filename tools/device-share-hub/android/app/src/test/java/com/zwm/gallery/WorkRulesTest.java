package com.zwm.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;

public final class WorkRulesTest {
    @Test
    public void recognizesSupportedImagesCaseInsensitively() {
        assertTrue(WorkRules.isSupportedImage("封面.JPG"));
        assertTrue(WorkRules.isSupportedImage("第二张.heic"));
        assertFalse(WorkRules.isSupportedImage("文案.txt"));
    }

    @Test
    public void prefersNamedCaptionThenNaturalOrder() {
        assertEquals("文案.txt", WorkRules.chooseCaption(Arrays.asList("说明.txt", "文案.txt", "2.txt")));
        assertEquals("2.txt", WorkRules.chooseCaption(Arrays.asList("10.txt", "2.txt")));
    }

    @Test
    public void sortsNumberedImagesNaturally() {
        assertTrue(WorkRules.compareNatural("作品2.png", "作品10.png") < 0);
        assertTrue(WorkRules.compareNatural("01.png", "2.png") < 0);
    }

    @Test
    public void movesSharedWorkOnNextCalendarDay() {
        LocalDate shared = LocalDate.of(2026, 7, 18);
        assertFalse(RetentionPolicy.shouldMoveToTrash(shared, shared));
        assertTrue(RetentionPolicy.shouldMoveToTrash(shared, shared.plusDays(1)));
    }

    @Test
    public void purgesTrashAfterSevenCalendarDays() {
        LocalDate trashed = LocalDate.of(2026, 7, 19);
        assertFalse(RetentionPolicy.shouldPurge(trashed, trashed.plusDays(6)));
        assertTrue(RetentionPolicy.shouldPurge(trashed, trashed.plusDays(7)));
    }
}
