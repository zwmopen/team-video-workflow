import Foundation

/// Keeps publishable copy separate from workflow metadata files in a work folder.
enum CaptionFilePolicy {
    private static let preferredNames = ["文案.txt", "小红书文案.txt", "抖音文案.txt"]
    private static let metadataNames = Set([
        "会话追踪.txt", "生产对话轨迹.txt", "生产记录.txt", "质量报告.txt", "作品标签.txt",
        "标签.txt", "元数据.txt", "metadata.txt", "manifest.txt",
        "日志.txt", "log.txt", "production-turns.txt", "session-tracking.txt"
    ])
    private static let metadataPrefixes = [
        "会话追踪", "生产对话轨迹", "生产记录", "质量报告", "作品标签", "标签", "元数据",
        "metadata", "manifest", "日志", "log", "production-turns", "session-tracking"
    ]

    static func choose(from urls: [URL]) -> URL? {
        for preferredName in preferredNames {
            if let match = urls.first(where: {
                $0.lastPathComponent.caseInsensitiveCompare(preferredName) == .orderedSame
            }) {
                return match
            }
        }
        return urls.first(where: { isCandidate($0.lastPathComponent) })
    }

    private static func isCandidate(_ name: String) -> Bool {
        let normalized = name.lowercased()
        guard normalized.hasSuffix(".txt") else { return false }
        if metadataNames.contains(normalized) { return false }
        let stem = String(normalized.dropLast(4))
        return !metadataPrefixes.contains { prefix in
            stem == prefix || stem.hasPrefix(prefix + "-") || stem.hasPrefix(prefix + "_")
                || stem.hasPrefix(prefix + " ") || stem.hasPrefix(prefix + "(")
                || stem.hasPrefix(prefix + "（")
        }
    }
}
