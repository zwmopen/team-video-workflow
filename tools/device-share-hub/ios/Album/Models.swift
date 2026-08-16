import Foundation

struct WorkItem: Identifiable, Hashable {
    let key: String
    let name: String
    let relativePath: String
    let folderURL: URL
    let textURL: URL
    let imageURLs: [URL]
    let shareCount: Int
    let xhsShareCount: Int
    let douyinShareCount: Int
    let used: Bool
    let category: String

    var id: String { key }
}

struct TrashItem: Identifiable, Hashable {
    let key: String
    let name: String
    let originalRelativePath: String
    let folderURL: URL
    let shareCount: Int
    let trashedDate: Date?

    var id: String { key }
}

struct LibraryState: Codable {
    var schemaVersion = 4
    var works: [String: WorkState] = [:]
    var history: [String: WorkState] = [:]

    init() { }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 3
        works = try container.decodeIfPresent([String: WorkState].self, forKey: .works) ?? [:]
        history = try container.decodeIfPresent([String: WorkState].self, forKey: .history) ?? [:]
    }
}

struct WorkState: Codable {
    var shareCount = 0
    var xhsShareCount = 0
    var douyinShareCount = 0
    var used = false
    var lastShareDate: String?
    var trashedDate: String?
    var firstSharedAtMs: Double?
    var firstUsedAtMs: Double?
    var deleteScheduledAtMs: Double?
    var trashedAtMs: Double?
    var originalRelativePath: String?
    var trashFolderName: String?

    init() { }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        shareCount = try container.decodeIfPresent(Int.self, forKey: .shareCount) ?? 0
        xhsShareCount = try container.decodeIfPresent(Int.self, forKey: .xhsShareCount) ?? 0
        douyinShareCount = try container.decodeIfPresent(Int.self, forKey: .douyinShareCount) ?? 0
        lastShareDate = try container.decodeIfPresent(String.self, forKey: .lastShareDate)
        trashedDate = try container.decodeIfPresent(String.self, forKey: .trashedDate)
        firstSharedAtMs = try container.decodeIfPresent(Double.self, forKey: .firstSharedAtMs)
        firstUsedAtMs = try container.decodeIfPresent(Double.self, forKey: .firstUsedAtMs)
        deleteScheduledAtMs = try container.decodeIfPresent(Double.self, forKey: .deleteScheduledAtMs)
        trashedAtMs = try container.decodeIfPresent(Double.self, forKey: .trashedAtMs)
        originalRelativePath = try container.decodeIfPresent(String.self, forKey: .originalRelativePath)
        trashFolderName = try container.decodeIfPresent(String.self, forKey: .trashFolderName)
        used = (try container.decodeIfPresent(Bool.self, forKey: .used)) ?? (shareCount > 0)
        if firstUsedAtMs == nil { firstUsedAtMs = firstSharedAtMs }
    }
}

enum LibraryError: LocalizedError {
    case noFolder
    case folderUnavailable
    case hiddenFolder
    case noText(String)
    case emptyText
    case missingPlatformCopy(String)
    case unreadableCopy
    case noImages(String)
    case stateWriteFailed
    case restoreConflict(String)
    case operationFailed(String)

    var errorDescription: String? {
        switch self {
        case .noFolder: return "请先选择作品总文件夹。"
        case .folderUnavailable: return "作品文件夹不可用，请重新选择。"
        case .hiddenFolder: return "不能选择 .Trash 等隐藏系统目录。请选择里面直接放着“作品一、作品二…”的总文件夹。"
        case .noText(let name): return "“\(name)”没有可读取的 TXT 文案。"
        case .emptyText: return "文案是空的，请先检查 TXT。"
        case .missingPlatformCopy(let platform): return "当前作品未找到\(platform)文案。"
        case .unreadableCopy: return "文案读取失败。"
        case .noImages(let name): return "“\(name)”没有可读取的图片。"
        case .stateWriteFailed: return "分享记录保存失败，本次没有打开分享。"
        case .restoreConflict(let name): return "已有同名作品“\(name)”，未覆盖任何文件。"
        case .operationFailed(let message): return message
        }
    }
}
