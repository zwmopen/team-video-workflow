import XCTest
@testable import Album

final class IncomingTransferTests: XCTestCase {
    func testSHA256MatchesKnownVector() {
        var state = SHA256.State()
        state.update(Data("abc".utf8))
        let value = state.finalize().map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(value, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testStoredZipRejectsTraversal() {
        XCTAssertThrowsError(try StoredZipExtractor.safeRelativePath("../outside.txt"))
        XCTAssertThrowsError(try StoredZipExtractor.safeRelativePath("C:\\outside.txt"))
        XCTAssertEqual(try StoredZipExtractor.safeRelativePath("作品包\\作品一\\文案.txt"),
                       "作品包/作品一/文案.txt")
    }

    func testStoredFolderZipExtractsWithoutOverwritingExistingFolder() throws {
        let temporary = FileManager.default.temporaryDirectory
            .appendingPathComponent("AlbumTransferTests-\(UUID().uuidString)", isDirectory: true)
        let root = temporary.appendingPathComponent("root", isDirectory: true)
        let archive = temporary.appendingPathComponent("folder.zip")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("作品包"),
                                                withIntermediateDirectories: true)
        try makeStoredZip(entries: [
            ("作品包/作品一/文案.txt", Data("测试文案".utf8)),
            ("作品包/作品一/01.jpg", Data([1, 2, 3, 4]))
        ]).write(to: archive)
        defer { try? FileManager.default.removeItem(at: temporary) }

        XCTAssertEqual(try StoredZipExtractor.extract(archive, to: root), 2)
        let imported = root.appendingPathComponent("作品包 (1)/作品一/文案.txt")
        XCTAssertEqual(try String(contentsOf: imported, encoding: .utf8), "测试文案")
    }

    func testStandardDeflatedZipExtracts() throws {
        let temporary = FileManager.default.temporaryDirectory
            .appendingPathComponent("AlbumDeflateTests-\(UUID().uuidString)", isDirectory: true)
        let root = temporary.appendingPathComponent("root", isDirectory: true)
        let archive = temporary.appendingPathComponent("standard.zip")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data(deflatedZipFixture).write(to: archive)
        defer { try? FileManager.default.removeItem(at: temporary) }

        XCTAssertEqual(try StoredZipExtractor.extract(archive, to: root), 2)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("pack/work/caption.txt"),
                                  encoding: .utf8), "caption")
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("pack/work/01.jpg")),
                       Data([1, 2, 3, 4]))
    }

    private var deflatedZipFixture: [UInt8] {
        return [
            0x50,0x4b,0x03,0x04,0x14,0x00,0x00,0x00,0x08,0x00,0x34,0x01,0xf4,0x5c,0x65,0x49,
            0x7c,0xf6,0x09,0x00,0x00,0x00,0x07,0x00,0x00,0x00,0x15,0x00,0x00,0x00,0x70,0x61,
            0x63,0x6b,0x2f,0x77,0x6f,0x72,0x6b,0x2f,0x63,0x61,0x70,0x74,0x69,0x6f,0x6e,0x2e,
            0x74,0x78,0x74,0x4b,0x4e,0x2c,0x28,0xc9,0xcc,0xcf,0x03,0x00,0x50,0x4b,0x03,0x04,
            0x14,0x00,0x00,0x00,0x08,0x00,0x34,0x01,0xf4,0x5c,0xcd,0xfb,0x3c,0xb6,0x06,0x00,
            0x00,0x00,0x04,0x00,0x00,0x00,0x10,0x00,0x00,0x00,0x70,0x61,0x63,0x6b,0x2f,0x77,
            0x6f,0x72,0x6b,0x2f,0x30,0x31,0x2e,0x6a,0x70,0x67,0x63,0x64,0x62,0x66,0x01,0x00,
            0x50,0x4b,0x01,0x02,0x14,0x00,0x14,0x00,0x00,0x00,0x08,0x00,0x34,0x01,0xf4,0x5c,
            0x65,0x49,0x7c,0xf6,0x09,0x00,0x00,0x00,0x07,0x00,0x00,0x00,0x15,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x80,0x01,0x00,0x00,0x00,0x00,0x70,0x61,
            0x63,0x6b,0x2f,0x77,0x6f,0x72,0x6b,0x2f,0x63,0x61,0x70,0x74,0x69,0x6f,0x6e,0x2e,
            0x74,0x78,0x74,0x50,0x4b,0x01,0x02,0x14,0x00,0x14,0x00,0x00,0x00,0x08,0x00,0x34,
            0x01,0xf4,0x5c,0xcd,0xfb,0x3c,0xb6,0x06,0x00,0x00,0x00,0x04,0x00,0x00,0x00,0x10,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x80,0x01,0x3c,0x00,0x00,
            0x00,0x70,0x61,0x63,0x6b,0x2f,0x77,0x6f,0x72,0x6b,0x2f,0x30,0x31,0x2e,0x6a,0x70,
            0x67,0x50,0x4b,0x05,0x06,0x00,0x00,0x00,0x00,0x02,0x00,0x02,0x00,0x81,0x00,0x00,
            0x00,0x70,0x00,0x00,0x00,0x00,0x00
        ]
    }

    private func makeStoredZip(entries: [(String, Data)]) -> Data {
        var result = Data()
        for (name, body) in entries {
            let nameData = Data(name.utf8)
            append32(0x04034b50, to: &result)
            append16(20, to: &result)
            append16(0x0800, to: &result)
            append16(0, to: &result)
            append16(0, to: &result)
            append16(0, to: &result)
            append32(0, to: &result)
            append32(UInt32(body.count), to: &result)
            append32(UInt32(body.count), to: &result)
            append16(UInt16(nameData.count), to: &result)
            append16(0, to: &result)
            result.append(nameData)
            result.append(body)
        }
        return result
    }

    private func append16(_ value: UInt16, to data: inout Data) {
        data.append(UInt8(value & 0xff))
        data.append(UInt8((value >> 8) & 0xff))
    }

    private func append32(_ value: UInt32, to data: inout Data) {
        append16(UInt16(value & 0xffff), to: &data)
        append16(UInt16((value >> 16) & 0xffff), to: &data)
    }
}
