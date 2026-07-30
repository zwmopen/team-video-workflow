package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ClipboardDisplayPolicyTest {
    @Test
    public void keepsShortClipboardExpandedWithoutExtraControl() {
        assertFalse(ClipboardDisplayPolicy.shouldCollapse("一句短内容"));
        assertFalse(ClipboardDisplayPolicy.shouldCollapse("第一行\n第二行\n第三行"));
    }

    @Test
    public void collapsesLongOrMultiLineClipboardByDefault() {
        assertTrue(ClipboardDisplayPolicy.shouldCollapse("一\n二\n三\n四"));
        assertTrue(ClipboardDisplayPolicy.shouldCollapse(
                new String(new char[141]).replace('\0', '长')));
    }
}
