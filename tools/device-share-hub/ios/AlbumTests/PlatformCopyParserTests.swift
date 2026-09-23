import XCTest
@testable import Album

final class PlatformCopyParserTests: XCTestCase {
    func testParsesBothPlatformsAndPreservesLineBreaks() {
        let source = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n标题\n正文\n<<<XHS_END>>>\n"
            + "<<<DOUYIN_START>>>\n口播一\n口播二\n<<<DOUYIN_END>>>"
        XCTAssertEqual(PlatformCopyParser.parse(source, platform: .xhs),
                       PlatformCopyResult(status: .ok, text: "标题\n正文"))
        XCTAssertEqual(PlatformCopyParser.parse(source, platform: .douyin),
                       PlatformCopyResult(status: .ok, text: "口播一\n口播二"))
    }

    func testLegacyTextIsAvailableToBothPlatforms() {
        let source = "旧格式第一行\n旧格式第二行"
        XCTAssertEqual(PlatformCopyParser.parse(source, platform: .xhs).text, source)
        XCTAssertEqual(PlatformCopyParser.parse(source, platform: .douyin).text, source)
    }

    func testMissingAndDamagedSectionsAreDifferent() {
        let onlyXhs = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n小红书\n<<<XHS_END>>>"
        XCTAssertEqual(PlatformCopyParser.parse(onlyXhs, platform: .douyin).status, .missing)
        let damaged = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n未闭合"
        XCTAssertEqual(PlatformCopyParser.parse(damaged, platform: .xhs).status, .unreadable)
    }

    func testParsesThreeSectionsInFormat3() {
        let source = "<<<COPY_FORMAT:3>>>\n<<<XHS_START>>>\n种草版标题\n种草版正文\n<<<XHS_END>>>\n"
            + "<<<XHS_2_START>>>\n大纲方案标题\n大纲方案正文\n<<<XHS_2_END>>>\n"
            + "<<<DOUYIN_START>>>\n避坑版第一行\n避坑版第二行\n<<<DOUYIN_END>>>"
        XCTAssertEqual(PlatformCopyParser.parse(source, platform: .xhs),
                       PlatformCopyResult(status: .ok, text: "种草版标题\n种草版正文"))
        XCTAssertEqual(PlatformCopyParser.parse(source, platform: .xhs2),
                       PlatformCopyResult(status: .ok, text: "大纲方案标题\n大纲方案正文"))
        XCTAssertEqual(PlatformCopyParser.parse(source, platform: .douyin),
                       PlatformCopyResult(status: .ok, text: "避坑版第一行\n避坑版第二行"))

        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 3)
        XCTAssertEqual(available[0].buttonLabel, "规避营销版")
        XCTAssertEqual(available[1].buttonLabel, "种草版")
        XCTAssertEqual(available[2].buttonLabel, "大纲方案版")
    }

    func testFormat2YieldsTwoButtons() {
        let source = "<<<COPY_FORMAT:2>>>\n<<<XHS_START>>>\n小红书文案\n<<<XHS_END>>>\n"
            + "<<<DOUYIN_START>>>\n抖音文案\n<<<DOUYIN_END>>>"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 2)
        XCTAssertEqual(available[0].buttonLabel, "规避营销版")
        XCTAssertEqual(available[1].buttonLabel, "种草版")
    }

    func testLegacyYieldsSingleButton() {
        let source = "旧版纯文案"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 1)
        XCTAssertEqual(available[0].buttonLabel, "发布")
        XCTAssertEqual(available[0].copyText, "旧版纯文案")
    }

    /// 与 Android `PlatformCopyParserTest.parsesMultipleExtendedVersionsAndCustomMarkers` 对齐。
    func testParsesMultipleExtendedVersionsAndCustomMarkers() {
        let source = "<<<COPY_FORMAT:3>>>\n"
            + "<<<XHS_START>>>\n小红书文案\n<<<XHS_END>>>\n"
            + "<<<DOUYIN_START>>>\n抖音文案\n<<<DOUYIN_END>>>\n"
            + "<<<WECHAT_START>>>\n微信公众号详细版文案\n<<<WECHAT_END>>>\n"
            + "<<<HR_START>>>\nHR决策版方案文案\n<<<HR_END>>>\n"
            + "<<<VERSION_4_START>>>\n第四版备用文案\n<<<VERSION_4_END>>>"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 5)
        XCTAssertEqual(available[0].buttonLabel, "规避营销版")
        XCTAssertEqual(available[1].buttonLabel, "种草版")
        XCTAssertEqual(available[2].buttonLabel, "公众号版")
        XCTAssertEqual(available[3].buttonLabel, "HR决策版")
        XCTAssertEqual(available[4].buttonLabel, "版本 4")
        XCTAssertEqual(available[2].copyText, "微信公众号详细版文案")
        XCTAssertEqual(available[3].copyText, "HR决策版方案文案")
        XCTAssertEqual(available[4].copyText, "第四版备用文案")
    }

    /// 回归（2026-09-21）：V4.5 多版本文案曾因解析器只认 `COPY_FORMAT:2/3` 被判为
    /// 「非协议文本」，退化成**一个「发布」按钮 + 含全部 `<<<VERSION_START:…>>>` 标记的整段原文**，
    /// 在真机上表现为「点文案按钮只有一段乱码、没有其他版本按钮」。
    /// 与 Android `parsesElevenMultiVersionsWithFourCharLabels` 对齐。
    func testParsesElevenMultiVersionsWithFourCharLabels() {
        let versions = ["数字爆款", "分天动线", "三箭头体", "杂志长条", "时间轴体", "原生种草",
                        "决策矩阵", "货架明细", "包院私享", "案例背书", "抖音避坑"]
        var source = "<<<COPY_FORMAT:MULTI>>>\n"
        for version in versions {
            source += "<<<VERSION_START:\(version)>>>\n\(version)标题\n\(version)正文\n<<<VERSION_END>>>\n"
        }
        source = String(source.dropLast())

        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 11)
        XCTAssertEqual(available.map { $0.buttonLabel }, versions)
        XCTAssertEqual(available[0].copyText, "数字爆款标题\n数字爆款正文")
        XCTAssertEqual(available[10].copyText, "抖音避坑标题\n抖音避坑正文")
        // 「抖音避坑」归到抖音平台，其余按通用版本处理（与 Android 一致）。
        XCTAssertEqual(available[10].platform, .douyin)
        XCTAssertEqual(available[0].platform, .general)
        // 正文里不得再残留任何协议标记（否则用户看到的还是「乱码」）。
        XCTAssertFalse(available.contains { $0.copyText.contains("<<<") })
    }

    /// 【2026-09-23 用户口径】版本名**不再截断到 4 字**：5 字的「抖音无营销」必须完整出按钮
    /// （此前会被 `String(trimmed.prefix(4))` 砍成「抖音无营」）。旧名仍能解析（向后兼容）。
    /// 与 Android `versionNameIsNoLongerTruncatedToFourChars` 对齐。
    func testVersionNameIsNoLongerTruncatedToFourChars() {
        XCTAssertEqual(PlatformCopyParser.friendlyLabelForMarker("抖音无营销"), "抖音无营销")
        XCTAssertEqual(PlatformCopyParser.friendlyLabelForMarker("红书种草"), "红书种草")
        XCTAssertEqual(PlatformCopyParser.friendlyLabelForMarker("红书大纲"), "红书大纲")
        // 旧名向后兼容：仍解析得出来，且同样保持完整字数
        XCTAssertEqual(PlatformCopyParser.friendlyLabelForMarker("抖音避坑"), "抖音避坑")
        // 固定标记的短标签语义一字不改
        XCTAssertEqual(PlatformCopyParser.friendlyLabelForMarker("发布"), "发布")
        XCTAssertEqual(PlatformCopyParser.friendlyLabelForMarker("XHS_3"), "短文精选版")
        XCTAssertEqual(PlatformCopyParser.friendlyLabelForMarker("VERSION_4"), "版本 4")
    }

    /// 【2026-09-23 用户口径】11 个版本**平级**，按钮顺序 = 文件里版本块出现的先后顺序。
    /// 老三家排在 `文案.txt` 最前，解析结果必须原样保持这个顺序（解析器本身不排序）。
    /// 与 Android `multiVersionButtonOrderFollowsFileOrder` 对齐。
    func testMultiVersionButtonOrderFollowsFileOrder() {
        let source = "<<<COPY_FORMAT:MULTI>>>\n"
            + "<<<VERSION_START:红书种草>>>\n甲\n<<<VERSION_END>>>\n"
            + "<<<VERSION_START:红书大纲>>>\n乙\n<<<VERSION_END>>>\n"
            + "<<<VERSION_START:抖音无营销>>>\n丙\n<<<VERSION_END>>>\n"
            + "<<<VERSION_START:数字爆款>>>\n丁\n<<<VERSION_END>>>"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 4)
        XCTAssertEqual(available.map { $0.buttonLabel }, ["红书种草", "红书大纲", "抖音无营销", "数字爆款"])
        // 抖音那一版归 douyin 平台，其余按通用版本处理（判定方式不变：名字含「抖音」）
        XCTAssertEqual(available[2].platform, .douyin)
        XCTAssertEqual(available[0].platform, .general)
    }

    /// 多版本必须**各自独立**：11 个版本里 10 个 `platform` 都是 `.general`，
    /// 若调用方按 platform 回查文案，点任何版本都会拿到第一条。
    func testMultiVersionItemsCarryTheirOwnText() {
        let source = "<<<COPY_FORMAT:MULTI>>>\n"
            + "<<<VERSION_START:数字爆款>>>\n甲文案\n<<<VERSION_END>>>\n"
            + "<<<VERSION_START:案例背书>>>\n乙文案\n<<<VERSION_END>>>"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 2)
        XCTAssertEqual(available[0].platform, available[1].platform)
        XCTAssertEqual(available[0].copyText, "甲文案")
        XCTAssertEqual(available[1].copyText, "乙文案")
    }

    /// 回归（2026-09-21）：MULTI 多版本文案经 `parse()` 时不得再整篇原文返回，
    /// 否则剪贴板里会塞满 `<<<VERSION_START:…>>>`（与 Android 同源缺陷）。
    func testMultiVersionTextNeverLeaksMarkersThroughParse() {
        let source = "<<<COPY_FORMAT:MULTI>>>\n"
            + "<<<VERSION_START:数字爆款>>>\n甲文案\n<<<VERSION_END>>>\n"
            + "<<<VERSION_START:案例背书>>>\n乙文案\n<<<VERSION_END>>>"
        for platform in CopyPlatform.allCases {
            let res = PlatformCopyParser.parse(source, platform: platform)
            XCTAssertFalse(res.text.contains("<<<"), "\(platform) 仍把协议标记放进了文案")
        }
        XCTAssertTrue(PlatformCopyParser.containsProtocolMarker(source))
    }

    /// 回归（2026-09-21）：磁盘上存在**畸形结束标记** `<<<DOUYIN_END>>`（少一个 `>`），
    /// 会被块正则当成正文吞进来 ⇒ 剪贴板里出现 `<<<DOUYIN_END>>`（实测 9 份作品命中）。
    func testMalformedEndMarkerIsStrippedFromExtractedCopy() {
        let source = "<<<COPY_FORMAT:3>>>\n<<<DOUYIN_START>>>\n口播正文\n<<<DOUYIN_END>>\n尾巴\n<<<DOUYIN_END>>>"
        let douyin = PlatformCopyParser.parse(source, platform: .douyin)
        XCTAssertTrue(douyin.isOK)
        XCTAssertFalse(douyin.text.contains("<<<"), "畸形标记漏进正文: \(douyin.text)")
        XCTAssertTrue(douyin.text.contains("口播正文"))
        XCTAssertTrue(douyin.text.contains("尾巴"))
        XCTAssertTrue(PlatformCopyParser.containsProtocolMarker(source))
    }

    /// 旧版纯文案（完全无标记）必须仍然原样返回，不得被误判成不可读。
    func testLegacyTextStillReturnedVerbatimForEveryPlatform() {
        let source = "旧格式第一行\n旧格式第二行"
        for platform in CopyPlatform.allCases {
            XCTAssertEqual(PlatformCopyParser.parse(source, platform: platform).text, source)
        }
        XCTAssertFalse(PlatformCopyParser.containsProtocolMarker(source))
    }

    // MARK: - DSH-087：`parseAvailablePlatforms` 出口净化

    /// 回归（2026-09-21 / DSH-087）：`parseAvailablePlatforms` 是**本地卡与在线卡共用**的
    /// 出口（ContentView:1282 `CopyParserCache` / ContentView:1474 `configureOnlineButtons`）。
    /// 此前它的 4 个 copyText 出口一处都没过净化，而 Android 同函数全过了
    /// ⇒ iOS 用户点文案按钮会拿到带 `<<<COPY_FORMAT:…>>>` 的文本（两端移植不对齐）。
    func testPseudoProtocolFallbackNeverLeaksHeader() {
        let source = "<<<COPY_FORMAT:3>>>\n\n【小红书自然种草版】\n正文一\n\n【抖音玩法避坑版】\n正文二"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 1)
        XCTAssertEqual(available[0].buttonLabel, "发布")
        XCTAssertFalse(available[0].copyText.contains("<<<"),
                       "兜底文案仍带协议标记: \(available[0].copyText)")
        XCTAssertTrue(available[0].copyText.contains("小红书自然种草版"))
    }

    /// 回归（2026-09-21 / DSH-087）：块正文里的**畸形标记**（`<<<DOUYIN_END>>` 少一个 `>`）
    /// 会被块正则当成正文吞进来，`parseAvailablePlatforms` 必须再剥一道。
    func testMalformedMarkerIsStrippedFromAvailablePlatforms() {
        let source = "<<<COPY_FORMAT:3>>>\n<<<DOUYIN_START>>>\n口播正文\n<<<DOUYIN_END>>\n尾巴\n<<<DOUYIN_END>>>"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertFalse(available.isEmpty)
        for item in available {
            XCTAssertFalse(item.copyText.contains("<<<"),
                           "\(item.buttonLabel) 漏标记: \(item.copyText)")
        }
    }

    /// 回归（2026-09-21 / DSH-087）：自定义标记块与多版本块两条出口都不得漏标记。
    /// 与 Android `customAndMultiBlockCopyNeverLeakMarkers` 对齐。
    func testCustomAndMultiBlockCopyNeverLeakMarkers() {
        let custom = "<<<COPY_FORMAT:3>>>\n<<<VERSION_4_START>>>\n备用文案\n<<<BOGUS_END>>\n尾\n<<<VERSION_4_END>>>"
        let customItems = PlatformCopyParser.parseAvailablePlatforms(custom)
        XCTAssertFalse(customItems.isEmpty)
        for item in customItems {
            XCTAssertFalse(item.copyText.contains("<<<"), "自定义标记块漏标记: \(item.copyText)")
        }
        let multi = "<<<COPY_FORMAT:MULTI>>>\n"
            + "<<<VERSION_START:数字爆款>>>\n甲文案\n<<<VERSION_END>>\n尾巴\n<<<VERSION_END>>>"
        for item in PlatformCopyParser.parseAvailablePlatforms(multi) {
            XCTAssertFalse(item.copyText.contains("<<<"), "多版本块漏标记: \(item.copyText)")
        }
    }
}
