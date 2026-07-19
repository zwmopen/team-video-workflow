import Foundation
import UIKit
import Combine

@MainActor
final class WorkLibrary: ObservableObject {
    @Published private(set) var works: [WorkItem] = []
    @Published private(set) var trash: [TrashItem] = []
    @Published private(set) var folderName: String?
    @Published var message: String?
    @Published var errorMessage: String?
    @Published var isBusy = false

    private let bookmarkKey = "album.rootFolderBookmark.v1"
    private let stateName = "_相册状态.json"
    private let trashName = "_相册回收站"
    private let imageExtensions = Set(["jpg", "jpeg", "png", "webp", "heic", "heif"])
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
                options: [.withoutUI, .withSecurityScope],
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
            errorMessage = "无法保存文件夹权限，请重新选择。"
        }
    }

    func clearFolder() {
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
        hasSecurityScope = false
        rootURL = nil
        folderName = nil
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
            state = try loadState(from: rootURL)
            try performMaintenance(root: rootURL)
            try saveState(to: rootURL)
            works = try scanWorks(root: rootURL)
            trash = try scanTrash(root: rootURL)
            folderName = rootURL.lastPathComponent
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

        var record = state.works[work.name] ?? WorkState()
        record.shareCount += 1
        record.lastShareDate = Self.dayFormatter.string(from: Date())
        record.trashedDate = nil
        state.works[work.name] = record
        do {
            guard let rootURL else { throw LibraryError.noFolder }
            try saveState(to: rootURL)
        } catch {
            throw LibraryError.stateWriteFailed
        }

        UIPasteboard.general.string = text
        message = "文案已复制 · 第 \(record.shareCount) 次打开分享"
        works = (try? scanWorks(root: rootURL!)) ?? works
        return work.imageURLs.map { $0 as NSURL }
    }

    func restore(_ item: TrashItem) async {
        guard let rootURL else { return }
        let destination = rootURL.appendingPathComponent(item.name, isDirectory: true)
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            errorMessage = LibraryError.restoreConflict(item.name).localizedDescription
            return
        }
        do {
            try FileManager.default.moveItem(at: item.folderURL, to: destination)
            var record = state.works[item.name] ?? WorkState()
            let previous = record
            record.lastShareDate = nil
            record.trashedDate = nil
            state.works[item.name] = record
            do {
                try saveState(to: rootURL)
            } catch {
                state.works[item.name] = previous
                try? FileManager.default.moveItem(at: destination, to: item.folderURL)
                throw error
            }
            message = "已恢复“\(item.name)”"
            await refresh()
        } catch {
            errorMessage = "恢复失败，原文件仍保留在回收站。"
        }
    }

    private func activate(_ url: URL) throws {
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
        let scoped = url.startAccessingSecurityScopedResource()
        guard FileManager.default.fileExists(atPath: url.path) else {
            if scoped { url.stopAccessingSecurityScopedResource() }
            throw LibraryError.folderUnavailable
        }
        rootURL = url
        hasSecurityScope = scoped
        folderName = url.lastPathComponent
    }

    private func saveBookmark(_ url: URL) throws {
        let data = try url.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: nil, relativeTo: nil)
        UserDefaults.standard.set(data, forKey: bookmarkKey)
    }

    private func scanWorks(root: URL) throws -> [WorkItem] {
        let folders = try childDirectories(of: root).filter {
            $0.lastPathComponent != trashName && !$0.lastPathComponent.hasPrefix("_相册状态")
        }
        return try folders.compactMap { folder in
            let files = try FileManager.default.contentsOfDirectory(
                at: folder,
                includingPropertiesForKeys: [.isRegularFileKey],
                options: [.skipsPackageDescendants]
            ).filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
            let images = NaturalSort.urls(files.filter { imageExtensions.contains($0.pathExtension.lowercased()) })
            let texts = NaturalSort.urls(files.filter { $0.pathExtension.lowercased() == "txt" })
            guard !images.isEmpty, !texts.isEmpty else { return nil }
            let preferred = texts.first { $0.lastPathComponent == "文案.txt" } ?? texts[0]
            return WorkItem(
                name: folder.lastPathComponent,
                folderURL: folder,
                textURL: preferred,
                imageURLs: images,
                shareCount: state.works[folder.lastPathComponent]?.shareCount ?? 0
            )
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private func scanTrash(root: URL) throws -> [TrashItem] {
        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)
        guard FileManager.default.fileExists(atPath: trashURL.path) else { return [] }
        return try childDirectories(of: trashURL).map { folder in
            let record = state.works[folder.lastPathComponent] ?? WorkState()
            return TrashItem(
                name: folder.lastPathComponent,
                folderURL: folder,
                shareCount: record.shareCount,
                trashedDate: record.trashedDate.flatMap { Self.dayFormatter.date(from: $0) }
            )
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private func childDirectories(of url: URL) throws -> [URL] {
        try FileManager.default.contentsOfDirectory(
            at: url,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsPackageDescendants]
        ).filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
    }

    private func performMaintenance(root: URL) throws {
        let today = Calendar.current.startOfDay(for: Date())
        let todayText = Self.dayFormatter.string(from: today)
        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)

        for (name, record) in Array(state.works) where record.trashedDate == nil {
            guard let lastText = record.lastShareDate,
                  let lastDate = Self.dayFormatter.date(from: lastText),
                  lastDate < today else { continue }
            let source = root.appendingPathComponent(name, isDirectory: true)
            guard FileManager.default.fileExists(atPath: source.path) else { continue }
            try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
            let destination = trashURL.appendingPathComponent(name, isDirectory: true)
            guard !FileManager.default.fileExists(atPath: destination.path) else { continue }
            try FileManager.default.moveItem(at: source, to: destination)
            var changed = record
            changed.trashedDate = todayText
            state.works[name] = changed
            do {
                try saveState(to: root)
            } catch {
                state.works[name] = record
                try? FileManager.default.moveItem(at: destination, to: source)
                throw error
            }
        }

        for (name, record) in Array(state.works) {
            guard let trashedText = record.trashedDate,
                  let trashedDate = Self.dayFormatter.date(from: trashedText),
                  let expiry = Calendar.current.date(byAdding: .day, value: 7, to: trashedDate),
                  expiry <= today else { continue }
            let folder = trashURL.appendingPathComponent(name, isDirectory: true)
            if FileManager.default.fileExists(atPath: folder.path) {
                try FileManager.default.removeItem(at: folder)
            }
            state.works.removeValue(forKey: name)
            try saveState(to: root)
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
