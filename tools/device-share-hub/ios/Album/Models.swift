import Foundation

struct WorkItem: Identifiable, Hashable {
    let key: String
    let name: String
    let relativePath: String
    let folderURL: URL
    let textURL: URL
    let imageURLs: [URL]
    let shareCount: Int

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
    var schemaVersion = 3
    var works: [String: WorkState] = [:]
}

struct WorkState: Codable {
    var shareCount = 0
    var lastShareDate: String?
    var trashedDate: String?
    var firstSharedAtMs: Double?
    var trashedAtMs: Double?
    var originalRelativePath: String?
    var trashFolderName: String?
}

enum LibraryError: LocalizedError {
    case noFolder
    case folderUnavailable
    case hiddenFolder
    case noText(String)
    case emptyText
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
        case .noImages(let name): return "“\(name)”没有可读取的图片。"
        case .stateWriteFailed: return "分享记录保存失败，本次没有打开分享。"
        case .restoreConflict(let name): return "已有同名作品“\(name)”，未覆盖任何文件。"
        case .operationFailed(let message): return message
        }
    }
}
