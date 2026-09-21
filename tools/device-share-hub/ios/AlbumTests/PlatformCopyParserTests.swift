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
}
