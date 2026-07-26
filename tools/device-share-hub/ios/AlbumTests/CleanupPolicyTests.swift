import XCTest
@testable import Album

final class CleanupPolicyTests: XCTestCase {
    func testOneHourBoundaryIsExact() {
        let now = Date(timeIntervalSince1970: 10_000)
        let anchor = now.timeIntervalSince1970 * 1000
        XCTAssertFalse(CleanupPolicy.isDue(
            anchorMilliseconds: anchor, hours: 1,
            now: now.addingTimeInterval(3_599.999)))
        XCTAssertTrue(CleanupPolicy.isDue(
            anchorMilliseconds: anchor, hours: 1,
            now: now.addingTimeInterval(3_600)))
    }

    func testYesterdayLegacyDateUsesBeijingStartOfDay() {
        let now = beijingDate(year: 2026, month: 7, day: 26, hour: 9)
        let anchor = CleanupPolicy.legacyAnchor(dateText: "2026-07-25", now: now)!
        XCTAssertTrue(CleanupPolicy.isDue(anchorMilliseconds: anchor, hours: 1, now: now))
    }

    func testSameDayLegacyDateGetsUpgradeGrace() {
        let now = beijingDate(year: 2026, month: 7, day: 26, hour: 9)
        let anchor = CleanupPolicy.legacyAnchor(dateText: "2026-07-26", now: now)!
        XCTAssertFalse(CleanupPolicy.isDue(
            anchorMilliseconds: anchor, hours: 1,
            now: now.addingTimeInterval(3_599)))
        XCTAssertTrue(CleanupPolicy.isDue(
            anchorMilliseconds: anchor, hours: 1,
            now: now.addingTimeInterval(3_600)))
    }

    private func beijingDate(year: Int, month: Int, day: Int, hour: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = CleanupPolicy.beijingTimeZone
        return calendar.date(from: DateComponents(
            timeZone: CleanupPolicy.beijingTimeZone,
            year: year, month: month, day: day, hour: hour))!
    }
}
