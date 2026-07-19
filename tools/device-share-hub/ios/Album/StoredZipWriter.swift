import Foundation

enum StoredZipWriterError: LocalizedError {
    case unreadableFolder, tooManyFiles, tooLarge
    var errorDescription: String? {
        switch self {
        case .unreadableFolder: return "无法读取所选文件夹"
        case .tooManyFiles: return "文件夹内文件超过 10000 个，请分批传送"
        case .tooLarge: return "单个文件超过 4GB，请单独传送"
        }
    }
}

enum StoredZipWriter {
    private struct Entry {
        let name: Data
        let crc: UInt32
        let size: UInt32
        let offset: UInt32
        let isDirectory: Bool
    }

    static func create(folder: URL, at output: URL) throws {
        let accessed = folder.startAccessingSecurityScopedResource()
        defer { if accessed { folder.stopAccessingSecurityScopedResource() } }
        try? FileManager.default.removeItem(at: output)
        FileManager.default.createFile(atPath: output.path, contents: nil)
        let handle = try FileHandle(forWritingTo: output)
        defer { handle.closeFile() }
        let root = safe(folder.lastPathComponent.isEmpty ? "文件夹" : folder.lastPathComponent)
        guard let enumerator = FileManager.default.enumerator(at: folder,
                                                               includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
                                                               options: []) else {
            throw StoredZipWriterError.unreadableFolder
        }
        var urls: [URL] = []
        while let item = enumerator.nextObject() as? URL {
            urls.append(item)
            if urls.count > 10_000 { throw StoredZipWriterError.tooManyFiles }
        }
        var entries: [Entry] = []
        var offset: UInt64 = 0
        for url in urls {
            let values = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            let isDirectory = values.isDirectory == true
            let relative = String(url.path.dropFirst(folder.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            var path = root + (relative.isEmpty ? "" : "/" + relative.replacingOccurrences(of: "\\", with: "/"))
            if isDirectory { path += "/" }
            guard let name = path.data(using: .utf8), name.count <= Int(UInt16.max) else { continue }
            var size: UInt64 = 0
            var crc = CRC32()
            if !isDirectory {
                let input = try FileHandle(forReadingFrom: url)
                defer { input.closeFile() }
                while true {
                    let data = input.readData(ofLength: 1024 * 1024)
                    if data.isEmpty { break }
                    size += UInt64(data.count)
                    guard size <= UInt64(UInt32.max) else { throw StoredZipWriterError.tooLarge }
                    crc.update(data)
                }
                input.seek(toFileOffset: 0)
                let header = localHeader(name: name, crc: crc.value, size: UInt32(size))
                handle.write(header)
                while true {
                    let data = input.readData(ofLength: 1024 * 1024)
                    if data.isEmpty { break }
                    handle.write(data)
                }
            } else {
                handle.write(localHeader(name: name, crc: 0, size: 0))
            }
            entries.append(Entry(name: name, crc: isDirectory ? 0 : crc.value,
                                 size: UInt32(size), offset: UInt32(offset), isDirectory: isDirectory))
            offset += UInt64(30 + name.count) + size
            guard offset <= UInt64(UInt32.max) else { throw StoredZipWriterError.tooLarge }
        }
        let centralOffset = UInt32(offset)
        for entry in entries {
            let data = centralHeader(entry)
            handle.write(data)
            offset += UInt64(data.count)
        }
        let centralSize = UInt32(offset - UInt64(centralOffset))
        var end = Data()
        end.appendLE(UInt32(0x06054b50)); end.appendLE(UInt16(0)); end.appendLE(UInt16(0))
        end.appendLE(UInt16(entries.count)); end.appendLE(UInt16(entries.count))
        end.appendLE(centralSize); end.appendLE(centralOffset); end.appendLE(UInt16(0))
        handle.write(end)
    }

    private static func localHeader(name: Data, crc: UInt32, size: UInt32) -> Data {
        var data = Data()
        data.appendLE(UInt32(0x04034b50)); data.appendLE(UInt16(20)); data.appendLE(UInt16(0x0800))
        data.appendLE(UInt16(0)); data.appendLE(UInt16(0)); data.appendLE(UInt16(0))
        data.appendLE(crc); data.appendLE(size); data.appendLE(size)
        data.appendLE(UInt16(name.count)); data.appendLE(UInt16(0)); data.append(name)
        return data
    }

    private static func centralHeader(_ entry: Entry) -> Data {
        var data = Data()
        data.appendLE(UInt32(0x02014b50)); data.appendLE(UInt16(20)); data.appendLE(UInt16(20))
        data.appendLE(UInt16(0x0800)); data.appendLE(UInt16(0)); data.appendLE(UInt16(0)); data.appendLE(UInt16(0))
        data.appendLE(entry.crc); data.appendLE(entry.size); data.appendLE(entry.size)
        data.appendLE(UInt16(entry.name.count)); data.appendLE(UInt16(0)); data.appendLE(UInt16(0))
        data.appendLE(UInt16(0)); data.appendLE(UInt16(0)); data.appendLE(entry.isDirectory ? UInt32(0x10) : UInt32(0))
        data.appendLE(entry.offset); data.append(entry.name)
        return data
    }

    private static func safe(_ value: String) -> String {
        let result = value.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_")
        return result.isEmpty ? "文件夹" : result
    }
}

private struct CRC32 {
    private(set) var value: UInt32 = 0
    mutating func update(_ data: Data) {
        var crc = value ^ 0xffffffff
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = (crc >> 1) ^ (crc & 1 == 1 ? 0xedb88320 : 0) }
        }
        value = crc ^ 0xffffffff
    }
}

private extension Data {
    mutating func appendLE(_ value: UInt16) {
        append(UInt8(value & 0xff)); append(UInt8((value >> 8) & 0xff))
    }
    mutating func appendLE(_ value: UInt32) {
        appendLE(UInt16(value & 0xffff)); appendLE(UInt16((value >> 16) & 0xffff))
    }
}
