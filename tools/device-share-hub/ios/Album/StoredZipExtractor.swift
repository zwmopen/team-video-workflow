import Foundation

enum StoredZipError: LocalizedError {
    case invalidArchive
    case unsafePath
    case unsupportedCompression
    case tooManyFiles
    case tooLarge

    var errorDescription: String? {
        switch self {
        case .invalidArchive: return "电脑文件夹传送包损坏，请重新传送。"
        case .unsafePath: return "传送包包含不安全路径，已拒绝展开。"
        case .unsupportedCompression: return "这个压缩包需要在“文件”App 中手动解压。"
        case .tooManyFiles: return "文件夹内文件过多，请分批传送。"
        case .tooLarge: return "文件夹内容过大，请分批传送。"
        }
    }
}

enum StoredZipExtractor {
    private static let localSignature: UInt32 = 0x04034b50
    private static let centralSignature: UInt32 = 0x02014b50
    private static let endSignature: UInt32 = 0x06054b50

    static func extract(_ archive: URL, to root: URL) throws -> Int {
        let staging = root.appendingPathComponent(".相册接收-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            let count = try extractEntries(archive, to: staging)
            let children = try FileManager.default.contentsOfDirectory(at: staging,
                                                                        includingPropertiesForKeys: nil)
            for child in children {
                let destination = uniqueDestination(for: child.lastPathComponent, under: root)
                try FileManager.default.moveItem(at: child, to: destination)
            }
            try FileManager.default.removeItem(at: staging)
            return count
        } catch {
            try? FileManager.default.removeItem(at: staging)
            throw error
        }
    }

    private static func extractEntries(_ archive: URL, to staging: URL) throws -> Int {
        let handle = try FileHandle(forReadingFrom: archive)
        defer { handle.closeFile() }
        var fileCount = 0
        var totalSize: UInt64 = 0
        while true {
            let signatureData = handle.readData(ofLength: 4)
            if signatureData.isEmpty { break }
            guard signatureData.count == 4 else { throw StoredZipError.invalidArchive }
            let signature = signatureData.le32(0)
            if signature == centralSignature || signature == endSignature { break }
            guard signature == localSignature else { throw StoredZipError.invalidArchive }
            let header = handle.readData(ofLength: 26)
            guard header.count == 26 else { throw StoredZipError.invalidArchive }
            let flags = header.le16(2)
            let method = header.le16(4)
            let compressedSize = UInt64(header.le32(14))
            let uncompressedSize = UInt64(header.le32(18))
            let nameLength = Int(header.le16(22))
            let extraLength = Int(header.le16(24))
            guard flags & 0x0008 == 0 else { throw StoredZipError.unsupportedCompression }
            guard method == 0, compressedSize == uncompressedSize else {
                throw StoredZipError.unsupportedCompression
            }
            guard compressedSize <= 4 * 1024 * 1024 * 1024 else { throw StoredZipError.tooLarge }
            let nameData = handle.readData(ofLength: nameLength)
            guard nameData.count == nameLength,
                  let rawName = String(data: nameData, encoding: .utf8) else {
                throw StoredZipError.invalidArchive
            }
            if extraLength > 0, handle.readData(ofLength: extraLength).count != extraLength {
                throw StoredZipError.invalidArchive
            }
            let normalized = try safeRelativePath(rawName)
            let destination = normalized.split(separator: "/").reduce(staging) {
                $0.appendingPathComponent(String($1))
            }
            if rawName.hasSuffix("/") || rawName.hasSuffix("\\") {
                try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
                continue
            }
            fileCount += 1
            totalSize += uncompressedSize
            guard fileCount <= 10_000 else { throw StoredZipError.tooManyFiles }
            guard totalSize <= 20 * 1024 * 1024 * 1024 else { throw StoredZipError.tooLarge }
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: destination.path, contents: nil)
            do {
                let output = try FileHandle(forWritingTo: destination)
                defer { output.closeFile() }
                var remaining = compressedSize
                while remaining > 0 {
                    let chunk = handle.readData(ofLength: Int(min(remaining, 1024 * 1024)))
                    guard !chunk.isEmpty else { throw StoredZipError.invalidArchive }
                    output.write(chunk)
                    remaining -= UInt64(chunk.count)
                }
            }
        }
        return fileCount
    }

    static func safeRelativePath(_ raw: String) throws -> String {
        let value = raw.replacingOccurrences(of: "\\", with: "/")
        guard !value.hasPrefix("/"), !value.contains(":") else { throw StoredZipError.unsafePath }
        let pieces = value.split(separator: "/", omittingEmptySubsequences: true)
        guard !pieces.isEmpty, pieces.allSatisfy({ $0 != "." && $0 != ".." }) else {
            throw StoredZipError.unsafePath
        }
        return pieces.joined(separator: "/")
    }

    static func uniqueDestination(for name: String, under root: URL) -> URL {
        let original = root.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: original.path) else { return original }
        let source = URL(fileURLWithPath: name)
        let ext = source.pathExtension
        let stem = source.deletingPathExtension().lastPathComponent
        for index in 1...9999 {
            let candidateName = ext.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
            let candidate = root.appendingPathComponent(candidateName)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        return root.appendingPathComponent("\(UUID().uuidString)-\(name)")
    }
}

private extension Data {
    func le16(_ offset: Int) -> UInt16 {
        return UInt16(self[index(startIndex, offsetBy: offset)]) |
            UInt16(self[index(startIndex, offsetBy: offset + 1)]) << 8
    }

    func le32(_ offset: Int) -> UInt32 {
        return UInt32(le16(offset)) | UInt32(le16(offset + 2)) << 16
    }
}
