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

    /// 【2026-09-23 用户口径】版本名**不再截断到 4 字**：5 字的「抖音无营销」必须完整出按钮
    /// （此前会被 `substring(0, 4)` 砍成「抖音无营」）。旧名仍能解析（向后兼容），也照样不截断。
    @Test
    public void versionNameIsNoLongerTruncatedToFourChars() {
        assertEquals("抖音无营销", PlatformCopyParser.friendlyLabelForMarker("抖音无营销"));
        assertEquals("红书种草", PlatformCopyParser.friendlyLabelForMarker("红书种草"));
        assertEquals("红书大纲", PlatformCopyParser.friendlyLabelForMarker("红书大纲"));
        // 旧名向后兼容：仍解析得出来，且同样保持完整字数
        assertEquals("抖音避坑", PlatformCopyParser.friendlyLabelForMarker("抖音避坑"));
        // 固定标记的短标签语义一字不改
        assertEquals("发布", PlatformCopyParser.friendlyLabelForMarker("发布"));
        assertEquals("短文精选版", PlatformCopyParser.friendlyLabelForMarker("XHS_3"));
        assertEquals("版本 4", PlatformCopyParser.friendlyLabelForMarker("VERSION_4"));
    }

    /// 【2026-09-23 用户口径】11 个版本**平级**，按钮顺序 = 文件里版本块出现的先后顺序。
    /// 老三家排在 `文案.txt` 最前，解析结果必须原样保持这个顺序（解析器本身不排序）。
    @Test
    public void multiVersionButtonOrderFollowsFileOrder() {
        String source = "<<<COPY_FORMAT:MULTI>>>\n"
                + "<<<VERSION_START:红书种草>>>\n甲\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:红书大纲>>>\n乙\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:抖音无营销>>>\n丙\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:数字爆款>>>\n丁\n<<<VERSION_END>>>";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(4, available.size());
        assertEquals("红书种草", available.get(0).buttonLabel);
        assertEquals("红书大纲", available.get(1).buttonLabel);
        assertEquals("抖音无营销", available.get(2).buttonLabel);
        assertEquals("数字爆款", available.get(3).buttonLabel);
        // 抖音那一版归 DOUYIN 平台，其余按通用版本处理（判定方式不变：名字含「抖音」）
        assertEquals(PlatformCopyParser.Platform.DOUYIN, available.get(2).platform);
        assertEquals(PlatformCopyParser.Platform.GENERAL, available.get(0).platform);
    }

    /// 回归（2026-09-21）：MULTI 多版本文案既无 `COPY_FORMAT:2/3` 头、也无该平台固定标记，
    /// 此前 `parse()` 会**整篇原文**返回 ⇒ 剪贴板里塞满 `<<<VERSION_START:…>>>`。
    /// 现在必须不再返回任何协议标记。
    @Test
    public void multiVersionTextNeverLeaksMarkersThroughParse() {
        String source = "<<<COPY_FORMAT:MULTI>>>\n"
                + "<<<VERSION_START:数字爆款>>>\n甲文案\n<<<VERSION_END>>>\n"
                + "<<<VERSION_START:案例背书>>>\n乙文案\n<<<VERSION_END>>>";
        for (PlatformCopyParser.Platform platform : PlatformCopyParser.Platform.values()) {
            PlatformCopyParser.Result res = PlatformCopyParser.parse(source, platform);
            assertTrue("platform=" + platform + " 仍把协议标记放进了文案",
                    !res.text.contains("<<<"));
        }
    }

    /// 旧版纯文案（完全无标记）必须仍然按原样分发到各平台。
    @Test
    public void legacyTextStillReturnedVerbatim() {
        String source = "旧格式第一行\n旧格式第二行";
        assertEquals(source, PlatformCopyParser.parse(source, PlatformCopyParser.Platform.XHS).text);
        assertEquals(source, PlatformCopyParser.parse(source, PlatformCopyParser.Platform.GENERAL).text);
        assertTrue(!PlatformCopyParser.hasAnyProtocolMarker(source));
    }

    /// 回归（2026-09-21）：Codex「伪协议」文案（有 `COPY_FORMAT` 头、无任何平台标记）
    /// 以前会走「发布」兜底并把 `<<<COPY_FORMAT:3>>>` 原样带进剪贴板（实测 46 份命中）。
    @Test
    public void pseudoProtocolCopyNeverLeaksHeader() {
        String source = "<<<COPY_FORMAT:3>>>\n\n【小红书自然种草版】\n正文一\n\n【抖音玩法避坑版】\n正文二";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertEquals(1, available.size());
        assertEquals("发布", available.get(0).buttonLabel);
        assertTrue("兜底文案仍带协议标记", !available.get(0).copyText.contains("<<<"));
        assertTrue(available.get(0).copyText.contains("小红书自然种草版"));
    }

    /// 回归（2026-09-21）：磁盘上存在**畸形结束标记** `<<<DOUYIN_END>>`（少一个 `>`），
    /// 会被块正则当成正文吞进来 ⇒ 剪贴板里出现 `<<<DOUYIN_END>>`。
    @Test
    public void malformedEndMarkerIsStrippedFromExtractedCopy() {
        String source = "<<<COPY_FORMAT:3>>>\n<<<DOUYIN_START>>>\n口播正文\n<<<DOUYIN_END>>\n尾巴\n<<<DOUYIN_END>>>";
        PlatformCopyParser.Result douyin = PlatformCopyParser.parse(source, PlatformCopyParser.Platform.DOUYIN);
        assertTrue(douyin.isOk());
        assertTrue("畸形标记漏进正文: " + douyin.text, !douyin.text.contains("<<<"));
        assertTrue(douyin.text.contains("口播正文"));
    }

    /// 兜底合成必须剥净 MULTI 头与 VERSION 块，不能只剥一半。
    @Test
    public void synthesizeStripsEveryProtocolMarker() {
        String source = "<<<COPY_FORMAT:MULTI>>>\n"
                + "<<<VERSION_START:数字爆款>>>\n甲文案\n<<<VERSION_END>>>\n"
                + "<<<XHS_START>>>\n乙文案\n<<<XHS_END>>>";
        assertTrue(PlatformCopyParser.hasMultiVersionBlocks(source));
        assertTrue(!PlatformCopyParser.stripProtocolMarkers(source).contains("<<<"));
        assertTrue(!PlatformCopyParser.synthesizeDouyinCopy(source).contains("<<<"));
        assertTrue(!PlatformCopyParser.synthesizeOutlineCopy(source).contains("<<<"));
    }

    /// 回归（2026-09-21 / DSH-087）：`parseAvailablePlatforms` 是本地卡与在线卡共用的出口，
    /// 四个 copyText 出口必须逐个净化（iOS 侧曾整批漏掉，两端移植不对齐）。
    @Test
    public void malformedEndMarkerIsStrippedFromAvailablePlatforms() {
        String source = "<<<COPY_FORMAT:3>>>\n<<<DOUYIN_START>>>\n口播正文\n<<<DOUYIN_END>>\n尾巴\n<<<DOUYIN_END>>>";
        java.util.List<PlatformCopyParser.AvailableItem> available =
                PlatformCopyParser.parseAvailablePlatforms(source);
        assertTrue(!available.isEmpty());
        for (PlatformCopyParser.AvailableItem item : available) {
            assertTrue(item.buttonLabel + " 漏标记: " + item.copyText,
                    !item.copyText.contains("<<<"));
        }
    }

    /// 回归（2026-09-21 / DSH-087）：自定义标记块与多版本块两条出口都不得漏标记。
    /// 与 iOS `testCustomAndMultiBlockCopyNeverLeakMarkers` 对齐。
    @Test
    public void customAndMultiBlockCopyNeverLeakMarkers() {
        String custom = "<<<COPY_FORMAT:3>>>\n<<<VERSION_4_START>>>\n备用文案\n<<<BOGUS_END>>\n尾\n<<<VERSION_4_END>>>";
        java.util.List<PlatformCopyParser.AvailableItem> customItems =
                PlatformCopyParser.parseAvailablePlatforms(custom);
        assertTrue(!customItems.isEmpty());
        for (PlatformCopyParser.AvailableItem item : customItems) {
            assertTrue("自定义标记块漏标记: " + item.copyText, !item.copyText.contains("<<<"));
        }
        String multi = "<<<COPY_FORMAT:MULTI>>>\n"
                + "<<<VERSION_START:数字爆款>>>\n甲文案\n<<<VERSION_END>>\n尾巴\n<<<VERSION_END>>>";
        for (PlatformCopyParser.AvailableItem item : PlatformCopyParser.parseAvailablePlatforms(multi)) {
            assertTrue("多版本块漏标记: " + item.copyText, !item.copyText.contains("<<<"));
        }
    }
}
