import XCTest
@testable import Album

final class WorkCategoryTests: XCTestCase {
    func testRecognizesConversionMarkers() {
        XCTAssertEqual(WorkCategory.from(path: "作品/[转]/案例"), WorkCategory.conversion)
        XCTAssertEqual(WorkCategory.from(path: "作品/【转】案例"), WorkCategory.conversion)
    }

    func testRecognizesTrafficMarkers() {
        XCTAssertEqual(WorkCategory.from(path: "作品/[泛]/案例"), WorkCategory.traffic)
        XCTAssertEqual(WorkCategory.from(path: "作品/【泛】案例"), WorkCategory.traffic)
    }

    func testLeavesOtherWorksUncategorized() {
        XCTAssertEqual(WorkCategory.from(path: "作品/普通案例"), WorkCategory.uncategorized)
    }
}
