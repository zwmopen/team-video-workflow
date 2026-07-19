import Foundation
import UIKit
import Combine

private struct ScanStatistics {
    var visitedDirectories = 0
    var hiddenDirectories = 0
    var unreadableDirectories = 0
    var missingImages = 0
    var missingTexts = 0
    var reachedDepthLimit = false
    var reachedDirectoryLimit = false
}

private struct ScanResult {
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
        if works.isEmpty, statistics.visitedDirectories == 1, statistics.hiddenDirectories > 0 {
            parts.append("这里只看到 .Trash 等系统目录，请把作品移入此文件夹或重新选择实际作品目录")
        }
        return parts.joined(separator: "；") + "。"
    }
}

@MainActor
final class WorkLibrary: ObservableObject {
    @Published private(set) var works: [WorkItem] = []
    @Published private(set) var trash: [TrashItem] = []
    @Published private(set) var folderName: String?
    @Published private(set) var scanSummary: String?
    @Published var message: String?
    @Published var errorMessage: String?
    @Published var isBusy = false

    private let bookmarkKey = "album.rootFolderBookmark.v1"
    private let stateName = "_相册状态.json"
    private let trashName = "相册回收站"
    private let legacyTrashName = "_相册回收站"
    private let imageExtensions = Set(["jpg", "jpeg", "png", "webp", "heic", "heif"])
    private let maximumScanDepth = 8
    private let maximumScannedDirectories = 1_000
    private var rootURL: URL?
    private var hasSecurityScope = false
    private var state = LibraryState()

    deinit {
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
    }

    func start() async {
        guard let data = UserDefaults.standard.data(forKey: bookmarkKey) else { return }
        do {
            var stale = false
            let url = try URL(
                resolvingBookmarkData: data,
                options: [.withoutUI],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            try activate(url)
            if stale { try saveBookmark(url) }
            await refresh()
        } catch {
            clearFolder()
            errorMessage = "原作品文件夹授权已失效，请重新选择。"
        }
    }

    func selectFolder(_ url: URL) async {
        do {
            try activate(url)
            try saveBookmark(url)
            await refresh()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "无法保存文件夹权限，请重新选择。"
        }
    }

    func clearFolder() {
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
        hasSecurityScope = false
        rootURL = nil
        folderName = nil
        scanSummary = nil
        works = []
        trash = []
        state = LibraryState()
        UserDefaults.standard.removeObject(forKey: bookmarkKey)
    }

    func refresh() async {
        guard let rootURL else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            try migrateLegacyTrash(root: rootURL)
            state = try loadState(from: rootURL)
            try performMaintenance(root: rootURL)
            try saveState(to: rootURL)
            let result = try scanWorks(root: rootURL)
            works = result.works
            trash = try scanTrash(root: rootURL)
            folderName = rootURL.lastPathComponent
            scanSummary = result.summary
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "扫描失败：\(error.localizedDescription)"
        }
    }

    func prepareShare(_ work: WorkItem) throws -> [Any] {
        let text: String
        do {
            text = try String(contentsOf: work.textURL, encoding: .utf8)
        } catch {
            throw LibraryError.noText(work.name)
        }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LibraryError.emptyText
        }
        guard !work.imageURLs.isEmpty else { throw LibraryError.noImages(work.name) }

        var record = state.works[work.key] ?? WorkState()
        record.shareCount += 1
        record.lastShareDate = Self.dayFormatter.string(from: Date())
        record.trashedDate = nil
        record.originalRelativePath = work.relativePath
        state.works[work.key] = record
        do {
            guard let rootURL else { throw LibraryError.noFolder }
            try saveState(to: rootURL)
        } catch {
            throw LibraryError.stateWriteFailed
        }

        UIPasteboard.general.string = text
        message = "文案已复制 · 第 \(record.shareCount) 次打开分享"
        works = (try? scanWorks(root: rootURL!).works) ?? works
        return work.imageURLs.map { $0 as NSURL }
    }

    func restore(_ item: TrashItem) async {
        guard let rootURL else { return }
        let destination = url(forRelativePath: item.originalRelativePath, under: rootURL)
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            errorMessage = LibraryError.restoreConflict(item.name).localizedDescription
            return
        }
        do {
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try FileManager.default.moveItem(at: item.folderURL, to: destination)
            var record = state.works[item.key] ?? WorkState()
            let previous = record
            record.lastShareDate = nil
            record.trashedDate = nil
            record.trashFolderName = nil
            record.originalRelativePath = item.originalRelativePath
            state.works[item.key] = record
            do {
                try saveState(to: rootURL)
            } catch {
                state.works[item.key] = previous
                try? FileManager.default.moveItem(at: destination, to: item.folderURL)
                throw error
            }
            message = "已恢复“\(item.name)”"
            await refresh()
        } catch {
            errorMessage = "恢复失败，原文件仍保留在回收站。"
        }
    }

    func copyDiagnostics() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "未知"
        let lines = [
            "相册 iOS 诊断信息",
            "版本：\(version) (\(build))",
            "系统：iOS \(UIDevice.current.systemVersion)",
            "设备：\(UIDevice.current.model)",
            "目录授权：\(rootURL == nil ? "无" : "有")",
            "扫描结果：\(scanSummary ?? "未扫描")",
            "作品数量：\(works.count)",
            "回收站数量：\(trash.count)",
            "最近错误：\(errorMessage ?? "无")",
            "生成时间：\(ISO8601DateFormatter().string(from: Date()))",
            "说明：不包含文案、图片内容或完整文件路径"
        ]
        UIPasteboard.general.string = lines.joined(separator: "\n")
        message = "诊断信息已复制"
    }

    private func activate(_ url: URL) throws {
        guard !isHiddenDirectory(url) else { throw LibraryError.hiddenFolder }
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw LibraryError.folderUnavailable
        }
        let scoped = url.startAccessingSecurityScopedResource()
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
        rootURL = url
        hasSecurityScope = scoped
        folderName = url.lastPathComponent
    }

    private func saveBookmark(_ url: URL) throws {
        // iOS document-picker URLs carry an implicit security scope. The explicit
        // withSecurityScope bookmark option is macOS-only in the iOS SDK.
        let data = try url.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil)
        UserDefaults.standard.set(data, forKey: bookmarkKey)
    }

    private func scanWorks(root: URL) throws -> ScanResult {
        var statistics = ScanStatistics()
        var found: [WorkItem] = []

        func visit(_ folder: URL, relativeComponents: [String], depth: Int) throws {
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
                if !texts.isEmpty, images.isEmpty { statistics.missingImages += 1 }
                if !images.isEmpty, texts.isEmpty { statistics.missingTexts += 1 }
            }

            if !relativeComponents.isEmpty, !images.isEmpty, !texts.isEmpty {
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
                if isHiddenDirectory(child) {
                    statistics.hiddenDirectories += 1
                    continue
                }
                if child.lastPathComponent == trashName || child.lastPathComponent == legacyTrashName
                    || child.lastPathComponent.hasPrefix("_相册状态") {
                    continue
                }
                try visit(
                    child,
                    relativeComponents: relativeComponents + [child.lastPathComponent],
                    depth: depth + 1
                )
            }
        }

        try visit(root, relativeComponents: [], depth: 0)
        found.sort {
            $0.relativePath.localizedStandardCompare($1.relativePath) == .orderedAscending
        }
        return ScanResult(works: found, statistics: statistics)
    }

    private func scanTrash(root: URL) throws -> [TrashItem] {
        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)
        guard FileManager.default.fileExists(atPath: trashURL.path) else { return [] }
        return try childDirectories(of: trashURL).map { folder in
            let match = state.works.first { entry in
                entry.value.trashFolderName == folder.lastPathComponent ||
                    (entry.value.trashFolderName == nil && entry.key == folder.lastPathComponent)
            }
            let key = match?.key ?? folder.lastPathComponent
            let record = match?.value ?? WorkState()
            let originalRelativePath = record.originalRelativePath ?? key
            return TrashItem(
                key: key,
                name: URL(fileURLWithPath: originalRelativePath).lastPathComponent,
                originalRelativePath: originalRelativePath,
                folderURL: folder,
                shareCount: record.shareCount,
                trashedDate: record.trashedDate.flatMap { Self.dayFormatter.date(from: $0) }
            )
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private func childDirectories(of url: URL, includeHidden: Bool = false) throws -> [URL] {
        let directories = try FileManager.default.contentsOfDirectory(
            at: url,
            includingPropertiesForKeys: [.isDirectoryKey, .isHiddenKey],
            options: [.skipsPackageDescendants]
        ).filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
        return includeHidden ? directories : directories.filter { !isHiddenDirectory($0) }
    }

    private func isHiddenDirectory(_ url: URL) -> Bool {
        if url.lastPathComponent.hasPrefix(".") { return true }
        return (try? url.resourceValues(forKeys: [.isHiddenKey]).isHidden) == true
    }

    private func migrateLegacyTrash(root: URL) throws {
        let legacy = root.appendingPathComponent(legacyTrashName, isDirectory: true)
        guard FileManager.default.fileExists(atPath: legacy.path) else { return }
        let current = root.appendingPathComponent(trashName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: current.path) {
            try FileManager.default.moveItem(at: legacy, to: current)
            return
        }
        for child in try childDirectories(of: legacy, includeHidden: true) {
            let destination = current.appendingPathComponent(child.lastPathComponent, isDirectory: true)
            guard !FileManager.default.fileExists(atPath: destination.path) else { continue }
            try FileManager.default.moveItem(at: child, to: destination)
        }
        if (try FileManager.default.contentsOfDirectory(atPath: legacy.path)).isEmpty {
            try FileManager.default.removeItem(at: legacy)
        }
    }

    private func performMaintenance(root: URL) throws {
        let today = Calendar.current.startOfDay(for: Date())
        let todayText = Self.dayFormatter.string(from: today)
        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)

        for (key, record) in Array(state.works) where record.trashedDate == nil {
            guard let lastText = record.lastShareDate,
                  let lastDate = Self.dayFormatter.date(from: lastText),
                  lastDate < today else { continue }
            let originalRelativePath = record.originalRelativePath ?? key
            let source = url(forRelativePath: originalRelativePath, under: root)
            guard FileManager.default.fileExists(atPath: source.path) else { continue }
            try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
            let trashFolderName = record.trashFolderName ??
                "\(source.lastPathComponent)-\(UUID().uuidString.prefix(8))"
            let destination = trashURL.appendingPathComponent(trashFolderName, isDirectory: true)
            guard !FileManager.default.fileExists(atPath: destination.path) else { continue }
            try FileManager.default.moveItem(at: source, to: destination)
            var changed = record
            changed.trashedDate = todayText
            changed.originalRelativePath = originalRelativePath
            changed.trashFolderName = trashFolderName
            state.works[key] = changed
            do {
                try saveState(to: root)
            } catch {
                state.works[key] = record
                try? FileManager.default.moveItem(at: destination, to: source)
                throw error
            }
        }

        for (key, record) in Array(state.works) {
            guard let trashedText = record.trashedDate,
                  let trashedDate = Self.dayFormatter.date(from: trashedText),
                  let expiry = Calendar.current.date(byAdding: .day, value: 7, to: trashedDate),
                  expiry <= today else { continue }
            let folder = trashURL.appendingPathComponent(
                record.trashFolderName ?? URL(fileURLWithPath: key).lastPathComponent,
                isDirectory: true
            )
            if FileManager.default.fileExists(atPath: folder.path) {
                try FileManager.default.removeItem(at: folder)
            }
            state.works.removeValue(forKey: key)
            try saveState(to: root)
        }
    }

    private func url(forRelativePath path: String, under root: URL) -> URL {
        path.split(separator: "/").reduce(root) { partial, component in
            partial.appendingPathComponent(String(component), isDirectory: true)
        }
    }

    private func loadState(from root: URL) throws -> LibraryState {
        let url = root.appendingPathComponent(stateName)
        guard FileManager.default.fileExists(atPath: url.path) else { return LibraryState() }
        do {
            return try JSONDecoder().decode(LibraryState.self, from: Data(contentsOf: url))
        } catch {
            let stamp = Self.backupFormatter.string(from: Date())
            let backup = root.appendingPathComponent("_相册状态损坏-\(stamp).json")
            try FileManager.default.moveItem(at: url, to: backup)
            message = "分享记录已重建，作品文件没有删除。"
            return LibraryState()
        }
    }

    private func saveState(to root: URL) throws {
        let data = try JSONEncoder.pretty.encode(state)
        try data.write(to: root.appendingPathComponent(stateName), options: .atomic)
    }

    private static let dayFormatter: DateFormatter = {
        let value = DateFormatter()
        value.calendar = Calendar(identifier: .gregorian)
        value.locale = Locale(identifier: "en_US_POSIX")
        value.timeZone = .current
        value.dateFormat = "yyyy-MM-dd"
        return value
    }()

    private static let backupFormatter: DateFormatter = {
        let value = DateFormatter()
        value.locale = Locale(identifier: "en_US_POSIX")
        value.dateFormat = "yyyyMMdd-HHmmss"
        return value
    }()
}

private extension JSONEncoder {
    static let pretty: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return encoder
    }()
}
