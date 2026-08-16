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
}
