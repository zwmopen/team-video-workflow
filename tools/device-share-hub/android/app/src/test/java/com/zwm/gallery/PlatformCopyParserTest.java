package com.zwm.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlatformCopyParserTest {
    @Test
    public void parsesBothSectionsAndKeepsInternalLineBreaks() {
        String source = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n标题\n正文\n<<<XHS_END>>>\n"
                + "<<<DOUYIN_START>>>\n口播第一行\n口播第二行\n<<<DOUYIN_END>>>";
        PlatformCopyParser.Result xhs = PlatformCopyParser.parse(source, PlatformCopyParser.Platform.XHS);
        PlatformCopyParser.Result douyin = PlatformCopyParser.parse(source, PlatformCopyParser.Platform.DOUYIN);
        assertTrue(xhs.isOk());
        assertEquals("标题\n正文", xhs.text);
        assertEquals("口播第一行\n口播第二行", douyin.text);
    }

    @Test
    public void legacyTextIsAvailableToBothPlatforms() {
        String source = "旧格式第一行\n旧格式第二行";
        assertEquals(source, PlatformCopyParser.parse(source, PlatformCopyParser.Platform.XHS).text);
        assertEquals(source, PlatformCopyParser.parse(source, PlatformCopyParser.Platform.DOUYIN).text);
    }

    @Test
    public void missingSectionDoesNotFallbackToOtherPlatform() {
        String source = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n只有小红书\n<<<XHS_END>>>";
        assertEquals(PlatformCopyParser.Status.OK,
                PlatformCopyParser.parse(source, PlatformCopyParser.Platform.XHS).status);
        assertEquals(PlatformCopyParser.Status.MISSING,
                PlatformCopyParser.parse(source, PlatformCopyParser.Platform.DOUYIN).status);
    }

    @Test
    public void damagedSectionIsUnreadableButDoesNotCrash() {
        String source = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n未闭合";
        assertEquals(PlatformCopyParser.Status.UNREADABLE,
                PlatformCopyParser.parse(source, PlatformCopyParser.Platform.XHS).status);
    }
}
