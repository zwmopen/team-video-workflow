import Foundation

enum CopyPlatform: String {
    case xhs
    case douyin

    var displayName: String { self == .xhs ? "小红书" : "抖音" }
    var startMarker: String { self == .xhs ? "<<<XHS_START>>>" : "<<<DOUYIN_START>>>" }
    var endMarker: String { self == .xhs ? "<<<XHS_END>>>" : "<<<DOUYIN_END>>>" }
}

enum PlatformCopyStatus: Equatable {
    case ok
    case missing
    case unreadable
}

struct PlatformCopyResult: Equatable {
    let status: PlatformCopyStatus
    let text: String

    var isOK: Bool { status == .ok }
}

enum PlatformCopyParser {
    static let header = "<<<COPY_FORMAT:2>>>"

    static func parse(_ source: String?, platform: CopyPlatform) -> PlatformCopyResult {
        guard var value = source else { return PlatformCopyResult(status: .unreadable, text: "") }
        if value.first == "\u{FEFF}" { value.removeFirst() }
        guard value.contains(header) else {
            return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? PlatformCopyResult(status: .unreadable, text: "")
                : PlatformCopyResult(status: .ok, text: value)
        }

        guard let start = value.range(of: platform.startMarker) else {
            return PlatformCopyResult(status: .missing, text: "")
        }
        let afterStart = start.upperBound
        guard let end = value.range(of: platform.endMarker, range: afterStart..<value.endIndex) else {
            return PlatformCopyResult(status: .unreadable, text: "")
        }
        let content = value[afterStart..<end.lowerBound]
            .trimmingCharacters(in: CharacterSet(charactersIn: "\r\n"))
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return PlatformCopyResult(status: .unreadable, text: "")
        }
        return PlatformCopyResult(status: .ok, text: String(content))
    }
}
