import XCTest
@testable import Album

final class WorkScannerTests: XCTestCase {
    func testRecursivelyFindsOnlyFoldersContainingTextAndImages() throws {
        let root = temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let work = root.appendingPathComponent("作品包一/作品一", isDirectory: true)
        let incomplete = root.appendingPathComponent("作品包二/只有图片", isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: incomplete, withIntermediateDirectories: true)
        try Data("文案".utf8).write(to: work.appendingPathComponent("文案.txt"))
        try Data([1, 2, 3]).write(to: work.appendingPathComponent("01.jpg"))
        try Data([1]).write(to: incomplete.appendingPathComponent("01.png"))

        let result = try WorkScanner(excludedDirectoryNames: ["相册回收站"]).scan(
            root: root, state: LibraryState()
        )

        XCTAssertEqual(result.works.map { $0.relativePath }, ["作品包一/作品一"])
        XCTAssertEqual(result.statistics.missingTexts, 1)
    }

    func testSkipsHiddenAndTrashFoldersAtEveryDepth() throws {
        let root = temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        for name in [".隐藏作品", "相册回收站/旧作品", "相册回收站 (1)/旧作品", "_相册回收站 (2)/旧作品"] {
            let folder = root.appendingPathComponent(name, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try Data("文案".utf8).write(to: folder.appendingPathComponent("文案.txt"))
            try Data([1]).write(to: folder.appendingPathComponent("01.jpg"))
        }

        let result = try WorkScanner(excludedDirectoryNames: ["相册回收站", "_相册回收站"]).scan(
            root: root, state: LibraryState()
        )

        XCTAssertTrue(result.works.isEmpty)
    }

    private func temporaryFolder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }
}
