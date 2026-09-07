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

    func testFormatsFolderLabelsWithAdaptiveTruncation() {
        XCTAssertEqual(LibraryViewController.formatFolderLabel("全部"), "全部")
        XCTAssertEqual(LibraryViewController.formatFolderLabel(""), "全部")
        XCTAssertEqual(LibraryViewController.formatFolderLabel("all"), "全部")
        XCTAssertEqual(LibraryViewController.formatFolderLabel("安吉站"), "安吉站")
        XCTAssertEqual(LibraryViewController.formatFolderLabel("12345"), "12345")
        XCTAssertEqual(LibraryViewController.formatFolderLabel("作品集_100"), "作品集_1...")
        XCTAssertEqual(LibraryViewController.formatFolderLabel("超过五个字的内容"), "超过五个字...")
    }

    func testExtractsFolderNameFromWorkItem() {
        let work1 = WorkItem(key: "k1", name: "作品一", relativePath: "作品包一/作品一",
                             folderURL: URL(fileURLWithPath: "/tmp"), textURL: URL(fileURLWithPath: "/tmp/t.txt"),
                             imageURLs: [], shareCount: 0, xhsShareCount: 0, douyinShareCount: 0, used: false,
                             category: "all")
        XCTAssertEqual(work1.folderName, "作品包一")

        let work2 = WorkItem(key: "k2", name: "单作品", relativePath: "单作品",
                             folderURL: URL(fileURLWithPath: "/tmp"), textURL: URL(fileURLWithPath: "/tmp/t.txt"),
                             imageURLs: [], shareCount: 0, xhsShareCount: 0, douyinShareCount: 0, used: false,
                             category: "all")
        XCTAssertEqual(work2.folderName, "")
    }
}
