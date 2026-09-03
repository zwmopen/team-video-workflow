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
        XCTAssertEqual(available[0].buttonLabel, "发抖音")
        XCTAssertEqual(available[1].buttonLabel, "发小红书")
    }

    func testLegacyYieldsSingleButton() {
        let source = "旧版纯文案"
        let available = PlatformCopyParser.parseAvailablePlatforms(source)
        XCTAssertEqual(available.count, 1)
        XCTAssertEqual(available[0].buttonLabel, "发小红书")
        XCTAssertEqual(available[0].copyText, "旧版纯文案")
    }
}
