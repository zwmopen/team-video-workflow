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
        assertEquals("规避营销版", available.get(0).buttonLabel);
        assertEquals("种草版", available.get(1).buttonLabel);
        assertEquals("大纲方案版", available.get(2).buttonLabel);
    }

    @Test
    public void format2YieldsTwoButtons() {
        String source = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n小红书文案\n<<<XHS_END>>>\n"
                + "<<<DOUYIN_START>>>\n抖音文案\n<<<DOUYIN_END>>>";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(2, available.size());
        assertEquals("规避营销版", available.get(0).buttonLabel);
        assertEquals("种草版", available.get(1).buttonLabel);
    }

    @Test
    public void legacyYieldsSingleButton() {
        String source = "旧版纯文案";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(1, available.size());
        assertEquals("发布", available.get(0).buttonLabel);
        assertEquals("旧版纯文案", available.get(0).copyText);
    }

    @Test
    public void parsesMultipleExtendedVersionsAndCustomMarkers() {
        String source = "<<<COPY_FORMAT:3>>>\n"
                + "<<<XHS_START>>>\n小红书文案\n<<<XHS_END>>>\n"
                + "<<<DOUYIN_START>>>\n抖音文案\n<<<DOUYIN_END>>>\n"
                + "<<<WECHAT_START>>>\n微信公众号详细版文案\n<<<WECHAT_END>>>\n"
                + "<<<HR_START>>>\nHR决策版方案文案\n<<<HR_END>>>\n"
                + "<<<VERSION_4_START>>>\n第四版备用文案\n<<<VERSION_4_END>>>";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(5, available.size());
        assertEquals("规避营销版", available.get(0).buttonLabel);
        assertEquals("种草版", available.get(1).buttonLabel);
        assertEquals("公众号版", available.get(2).buttonLabel);
        assertEquals("HR决策版", available.get(3).buttonLabel);
        assertEquals("版本 4", available.get(4).buttonLabel);
        assertEquals("微信公众号详细版文案", available.get(2).copyText);
        assertEquals("HR决策版方案文案", available.get(3).copyText);
        assertEquals("第四版备用文案", available.get(4).copyText);
    }

    @Test
    public void parsesElevenMultiVersionsWithFourCharLabels() {
        String source = "<<<COPY_FORMAT:MULTI>>>\n"
                + "<<<VERSION_START:数字爆款>>>\n数字爆款标题\n数字爆款正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:分天动线>>>\n分天动线标题\n分天动线正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:三箭头体>>>\n三箭头体标题\n三箭头体正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:杂志长条>>>\n杂志长条标题\n杂志长条正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:时间轴体>>>\n时间轴体标题\n时间轴体正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:原生种草>>>\n原生种草标题\n原生种草正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:决策矩阵>>>\n决策矩阵标题\n决策矩阵正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:货架明细>>>\n货架明细标题\n货架明细正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:包院私享>>>\n包院私享标题\n包院私享正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:案例背书>>>\n案例背书标题\n案例背书正文\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:抖音避坑>>>\n抖音避坑标题\n抖音避坑正文\n<<<VERSION_END>>>";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(11, available.size());
        assertEquals("数字爆款", available.get(0).buttonLabel);
        assertEquals("分天动线", available.get(1).buttonLabel);
        assertEquals("三箭头体", available.get(2).buttonLabel);
        assertEquals("杂志长条", available.get(3).buttonLabel);
        assertEquals("时间轴体", available.get(4).buttonLabel);
        assertEquals("原生种草", available.get(5).buttonLabel);
        assertEquals("决策矩阵", available.get(6).buttonLabel);
        assertEquals("货架明细", available.get(7).buttonLabel);
        assertEquals("包院私享", available.get(8).buttonLabel);
        assertEquals("案例背书", available.get(9).buttonLabel);
        assertEquals("抖音避坑", available.get(10).buttonLabel);
        assertEquals(PlatformCopyParser.Platform.DOUYIN, available.get(10).platform);
    }
}
