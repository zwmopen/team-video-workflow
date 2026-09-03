import Foundation

enum CopyPlatform: String {
    case douyin
    case xhs
    case xhs2

    var displayName: String {
        switch self {
        case .douyin: return "规避营销版"
        case .xhs: return "种草版"
        case .xhs2: return "大纲方案版"
        }
    }
    var shortLabel: String {
        switch self {
        case .douyin: return "规避营销版"
        case .xhs: return "种草版"
        case .xhs2: return "大纲方案版"
        }
    }
    var startMarker: String {
        switch self {
        case .douyin: return "<<<DOUYIN_START>>>"
        case .xhs: return "<<<XHS_START>>>"
        case .xhs2: return "<<<XHS_2_START>>>"
        }
    }
    var endMarker: String {
        switch self {
        case .douyin: return "<<<DOUYIN_END>>>"
        case .xhs: return "<<<XHS_END>>>"
        case .xhs2: return "<<<XHS_2_END>>>"
        }
    }
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

struct AvailableCopyPlatform: Equatable {
    let platform: CopyPlatform
    let buttonLabel: String
    let copyText: String
}

enum PlatformCopyParser {
    static let headerV2 = "<<<COPY_FORMAT:2>>>"
    static let headerV3 = "<<<COPY_FORMAT:3>>>"

    static func parseAvailablePlatforms(_ source: String?) -> [AvailableCopyPlatform] {
        guard var value = source else { return [] }
        if value.first == "\u{FEFF}" { value.removeFirst() }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }

        let isProtocol = value.contains(headerV2) || value.contains(headerV3)
            || value.contains("<<<XHS_START>>>") || value.contains("<<<DOUYIN_START>>>")
            || value.contains("<<<XHS_2_START>>>")
        guard isProtocol else {
            return [AvailableCopyPlatform(platform: .xhs, buttonLabel: "发小红书", copyText: trimmed)]
        }

        var items: [AvailableCopyPlatform] = []
        let douyinRes = parse(value, platform: .douyin)
        let xhsRes = parse(value, platform: .xhs)
        let xhs2Res = parse(value, platform: .xhs2)
        let hasXhs2 = xhs2Res.isOK

        if douyinRes.isOK {
            let label = hasXhs2 ? "规避营销版" : "发抖音"
            items.append(AvailableCopyPlatform(platform: .douyin, buttonLabel: label, copyText: douyinRes.text))
        }
        if xhsRes.isOK {
            let label = hasXhs2 ? "种草版" : "发小红书"
            items.append(AvailableCopyPlatform(platform: .xhs, buttonLabel: label, copyText: xhsRes.text))
        }
        if hasXhs2 {
            items.append(AvailableCopyPlatform(platform: .xhs2, buttonLabel: "大纲方案版", copyText: xhs2Res.text))
        }
        return items
    }

    static func parse(_ source: String?, platform: CopyPlatform) -> PlatformCopyResult {
        guard var value = source else { return PlatformCopyResult(status: .unreadable, text: "") }
        if value.first == "\u{FEFF}" { value.removeFirst() }
        guard value.contains(headerV2) || value.contains(headerV3)
            || value.contains(platform.startMarker) else {
            return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? PlatformCopyResult(status: .unreadable, text: "")
                : PlatformCopyResult(status: .ok, text: value)
        }

        guard let start = value.range(of: platform.startMarker) else {
            let otherPlatforms: [CopyPlatform]
            switch platform {
            case .douyin: otherPlatforms = [.xhs, .xhs2]
            case .xhs: otherPlatforms = [.douyin, .xhs2]
            case .xhs2: otherPlatforms = [.douyin, .xhs]
            }
            let hasOther = otherPlatforms.contains { value.contains($0.startMarker) || value.contains($0.endMarker) }
            if !hasOther {
                return PlatformCopyResult(status: .unreadable, text: "")
            }
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
