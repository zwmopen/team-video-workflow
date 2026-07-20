import Foundation

struct ScanStatistics {
    var visitedDirectories = 0
    var hiddenDirectories = 0
    var unreadableDirectories = 0
    var missingImages = 0
    var missingTexts = 0
    var reachedDepthLimit = false
    var reachedDirectoryLimit = false
}

struct ScanResult {
    let works: [WorkItem]
    let statistics: ScanStatistics

    var summary: String {
        var parts = ["递归检查 \(max(0, statistics.visitedDirectories - 1)) 个文件夹，识别 \(works.count) 个作品"]
        if statistics.hiddenDirectories > 0 { parts.append("忽略 \(statistics.hiddenDirectories) 个隐藏目录") }
        if statistics.missingImages > 0 { parts.append("\(statistics.missingImages) 个候选缺图片") }
        if statistics.missingTexts > 0 { parts.append("\(statistics.missingTexts) 个候选缺 TXT") }
        if statistics.unreadableDirectories > 0 { parts.append("\(statistics.unreadableDirectories) 个目录无读取权限") }
        if statistics.reachedDepthLimit { parts.append("已到 8 层深度上限") }
        if statistics.reachedDirectoryLimit { parts.append("已到 1000 个目录上限") }
        return parts.joined(separator: "；") + "。"
    }
}

final class WorkScanner {
    private let imageExtensions = Set(["jpg", "jpeg", "png", "webp", "heic", "heif"])
    private let maximumScanDepth = 8
    private let maximumScannedDirectories = 1_000
    private let excludedDirectoryNames: Set<String>

    init(excludedDirectoryNames: Set<String>) {
        self.excludedDirectoryNames = excludedDirectoryNames
    }

    func scan(root: URL, state: LibraryState) throws -> ScanResult {
        var statistics = ScanStatistics()
        var found: [WorkItem] = []

        func visit(_ folder: URL, relativeComponents: [String], depth: Int) {
            guard statistics.visitedDirectories < maximumScannedDirectories else {
                statistics.reachedDirectoryLimit = true
                return
            }
            statistics.visitedDirectories += 1

            let entries: [URL]
            do {
                entries = try FileManager.default.contentsOfDirectory(
                    at: folder,
                    includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .isHiddenKey],
                    options: [.skipsPackageDescendants]
                )
            } catch {
                statistics.unreadableDirectories += 1
                return
            }

            let files = entries.filter {
                (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
            }
            let images = NaturalSort.urls(files.filter { imageExtensions.contains($0.pathExtension.lowercased()) })
            let texts = NaturalSort.urls(files.filter { $0.pathExtension.lowercased() == "txt" })

            if !relativeComponents.isEmpty {
                if !texts.isEmpty && images.isEmpty { statistics.missingImages += 1 }
                if !images.isEmpty && texts.isEmpty { statistics.missingTexts += 1 }
            }

            if !relativeComponents.isEmpty && !images.isEmpty && !texts.isEmpty {
                let relativePath = relativeComponents.joined(separator: "/")
                let preferred = texts.first { $0.lastPathComponent == "文案.txt" } ?? texts[0]
                found.append(WorkItem(
                    key: relativePath,
                    name: folder.lastPathComponent,
                    relativePath: relativePath,
                    folderURL: folder,
                    textURL: preferred,
                    imageURLs: images,
                    shareCount: state.works[relativePath]?.shareCount ?? 0
                ))
                return
            }

            guard depth < maximumScanDepth else {
                statistics.reachedDepthLimit = true
                return
            }
            let directories = entries.filter {
                (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
            }
            for child in NaturalSort.urls(directories) {
                if isHidden(child) {
                    statistics.hiddenDirectories += 1
                    continue
                }
                if isExcludedDirectoryName(child.lastPathComponent)
                    || child.lastPathComponent.hasPrefix("_相册状态") { continue }
                visit(child, relativeComponents: relativeComponents + [child.lastPathComponent], depth: depth + 1)
            }
        }

        visit(root, relativeComponents: [], depth: 0)
        found.sort { $0.relativePath.localizedStandardCompare($1.relativePath) == .orderedAscending }
        return ScanResult(works: found, statistics: statistics)
    }

    private func isHidden(_ url: URL) -> Bool {
        if url.lastPathComponent.hasPrefix(".") { return true }
        return (try? url.resourceValues(forKeys: [.isHiddenKey]).isHidden) == true
    }

    private func isExcludedDirectoryName(_ name: String) -> Bool {
        if excludedDirectoryNames.contains(name) { return true }
        return excludedDirectoryNames.contains { base in
            guard name.hasPrefix(base + " ("), name.hasSuffix(")") else { return false }
            let start = name.index(name.startIndex, offsetBy: base.count + 2)
            let number = name[start..<name.index(before: name.endIndex)]
            return !number.isEmpty && number.allSatisfy(\.isNumber)
        }
    }
}
