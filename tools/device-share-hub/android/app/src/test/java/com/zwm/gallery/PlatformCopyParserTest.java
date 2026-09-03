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

    @Test
    public void parsesThreeSectionsInFormat3() {
        String source = "<<<COPY_FORMAT:3>>>\n<<<XHS_START>>>\n种草版标题\n种草版正文\n<<<XHS_END>>>\n"
                + "<<<XHS_2_START>>>\n大纲方案标题\n大纲方案正文\n<<<XHS_2_END>>>\n"
                + "<<<DOUYIN_START>>>\n避坑版第一行\n避坑版第二行\n<<<DOUYIN_END>>>";
        PlatformCopyParser.Result xhs = PlatformCopyParser.parse(source, PlatformCopyParser.Platform.XHS);
        PlatformCopyParser.Result xhs2 = PlatformCopyParser.parse(source, PlatformCopyParser.Platform.XHS_2);
        PlatformCopyParser.Result douyin = PlatformCopyParser.parse(source, PlatformCopyParser.Platform.DOUYIN);
        assertTrue(xhs.isOk());
        assertEquals("种草版标题\n种草版正文", xhs.text);
        assertTrue(xhs2.isOk());
        assertEquals("大纲方案标题\n大纲方案正文", xhs2.text);
        assertTrue(douyin.isOk());
        assertEquals("避坑版第一行\n避坑版第二行", douyin.text);

        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(3, available.size());
        assertEquals("避坑版", available.get(0).buttonLabel);
        assertEquals("种草版", available.get(1).buttonLabel);
        assertEquals("大纲版", available.get(2).buttonLabel);
    }

    @Test
    public void format2YieldsTwoButtons() {
        String source = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n小红书文案\n<<<XHS_END>>>\n"
                + "<<<DOUYIN_START>>>\n抖音文案\n<<<DOUYIN_END>>>";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(2, available.size());
        assertEquals("发抖音", available.get(0).buttonLabel);
        assertEquals("发小红书", available.get(1).buttonLabel);
    }

    @Test
    public void legacyYieldsSingleButton() {
        String source = "旧版纯文案";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(1, available.size());
        assertEquals("发小红书", available.get(0).buttonLabel);
        assertEquals("旧版纯文案", available.get(0).copyText);
    }
}
