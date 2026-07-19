import Foundation
import UIKit

final class WorkLibrary {
    private(set) var works: [WorkItem] = []
    private(set) var trash: [TrashItem] = []
    private(set) var folderName: String?
    private(set) var scanSummary: String?
    private(set) var message: String?
    private(set) var errorMessage: String?
    private(set) var networkStatus = UserDefaults.standard.string(forKey: "album.lastNetworkStatus.v1") ?? "局域网接收未启动"
    private(set) var isBusy = false
    var onChange: (() -> Void)?

    private let bookmarkKey = "album.rootFolderBookmark.v1"
    private let stateName = "_相册状态.json"
    private let trashName = "相册回收站"
    private let legacyTrashName = "_相册回收站"
    private var rootURL: URL?
    private var hasSecurityScope = false
    private var usesManagedFolder = false
    private var state = LibraryState()
    private lazy var scanner = WorkScanner(excludedDirectoryNames: [trashName, legacyTrashName])

    var supportsExternalFolderSelection: Bool {
        if #available(iOS 13.0, *) { return true }
        return false
    }

    var rootDescription: String {
        if usesManagedFolder || !supportsExternalFolderSelection { return "我的 iPhone/相册" }
        return folderName ?? "未选择"
    }

    var receivingRootURL: URL? { return rootURL }

    deinit {
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
    }

    func start() {
        if !supportsExternalFolderSelection {
            do {
                try activate(Self.documentsURL(), securityScoped: false)
                refresh(showConfirmation: false)
            } catch { report(error) }
            return
        }
        guard let data = UserDefaults.standard.data(forKey: bookmarkKey) else {
            useManagedFolder(showConfirmation: false)
            return
        }
        do {
            var stale = false
            let url = try URL(resolvingBookmarkData: data, options: [.withoutUI],
                              relativeTo: nil, bookmarkDataIsStale: &stale)
            try activate(url, securityScoped: true)
            if stale { try saveBookmark(url) }
            refresh(showConfirmation: false)
        } catch {
            clearFolder()
            errorMessage = "原作品文件夹授权已失效，请重新选择。"
            notify()
        }
    }

    func useManagedFolder() { useManagedFolder(showConfirmation: true) }

    private func useManagedFolder(showConfirmation: Bool) {
        do {
            try activate(Self.documentsURL(), securityScoped: false)
            usesManagedFolder = true
            UserDefaults.standard.removeObject(forKey: bookmarkKey)
            refresh(showConfirmation: showConfirmation)
        } catch { report(error) }
    }

    func selectFolder(_ url: URL) {
        do {
            try activate(url, securityScoped: true)
            usesManagedFolder = false
            try saveBookmark(url)
            refresh(showConfirmation: true)
        } catch { report(error) }
    }

    func clearFolder() {
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
        hasSecurityScope = false
        rootURL = nil
        usesManagedFolder = false
        folderName = nil
        scanSummary = nil
        works = []
        trash = []
        state = LibraryState()
        UserDefaults.standard.removeObject(forKey: bookmarkKey)
        notify()
    }

    func refresh(showConfirmation: Bool = true) {
        guard let root = rootURL else { return }
        isBusy = true
        notify()
        defer { isBusy = false; notify() }
        do {
            try migrateLegacyTrash(root: root)
            state = try loadState(from: root)
            try performMaintenance(root: root)
            try saveState(to: root)
            let result = try scanner.scan(root: root, state: state)
            works = result.works
            trash = try scanTrash(root: root)
            folderName = root.lastPathComponent
            scanSummary = result.summary
            errorMessage = nil
            if showConfirmation { message = "已刷新 · \(works.count)" }
        } catch { report(error) }
    }

    func prepareShare(_ work: WorkItem) throws -> [Any] {
        let text: String
        do { text = try String(contentsOf: work.textURL, encoding: .utf8) }
        catch { throw LibraryError.noText(work.name) }
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
        guard let root = rootURL else { throw LibraryError.noFolder }
        do { try saveState(to: root) }
        catch { throw LibraryError.stateWriteFailed }

        UIPasteboard.general.string = text
        message = "文案已复制 · 第 \(record.shareCount) 次打开分享"
        works = (try? scanner.scan(root: root, state: state).works) ?? works
        notify()
        return work.imageURLs.map { $0 as NSURL }
    }

    func restore(_ item: TrashItem) {
        guard let root = rootURL else { return }
        let destination = url(forRelativePath: item.originalRelativePath, under: root)
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            errorMessage = LibraryError.restoreConflict(item.name).localizedDescription
            notify()
            return
        }
        do {
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try FileManager.default.moveItem(at: item.folderURL, to: destination)
            var record = state.works[item.key] ?? WorkState()
            let previous = record
            record.lastShareDate = nil
            record.trashedDate = nil
            record.trashFolderName = nil
            record.originalRelativePath = item.originalRelativePath
            state.works[item.key] = record
            do { try saveState(to: root) }
            catch {
                state.works[item.key] = previous
                try? FileManager.default.moveItem(at: destination, to: item.folderURL)
                throw error
            }
            message = "已恢复“\(item.name)”"
            refresh(showConfirmation: false)
        } catch {
            errorMessage = "恢复失败，原文件仍保留在回收站。"
            notify()
        }
    }

    func clearTrash() throws {
        guard let root = rootURL else { throw LibraryError.noFolder }
        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)
        if FileManager.default.fileExists(atPath: trashURL.path) {
            for child in try FileManager.default.contentsOfDirectory(at: trashURL,
                                                                      includingPropertiesForKeys: nil) {
                try FileManager.default.removeItem(at: child)
            }
        }
        state.works = state.works.filter { $0.value.trashedDate == nil }
        try saveState(to: root)
        message = "回收站已清空"
        refresh(showConfirmation: false)
    }

    func copyDiagnostics() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "未知"
        let lines = [
            "相册 iOS 诊断信息", "版本：\(version) (\(build))",
            "系统：iOS \(UIDevice.current.systemVersion)", "设备：\(DeviceIdentity.model)",
            "手机名称：\(DeviceIdentity.name)", "局域网接收：\(networkStatus)",
            "已发现设备：\(PeerDirectory.shared.diagnosticSummary())",
            "目录：\(rootDescription)", "扫描结果：\(scanSummary ?? "未扫描")",
            "作品数量：\(works.count)", "回收站数量：\(trash.count)",
            "最近错误：\(errorMessage ?? "无")",
            "生成时间：\(ISO8601DateFormatter().string(from: Date()))",
            "说明：不包含文案、图片内容或完整文件路径"
        ]
        UIPasteboard.general.string = lines.joined(separator: "\n")
        message = "诊断信息已复制"
        notify()
    }

    func consumeMessage() { message = nil }
    func consumeError() { errorMessage = nil }

    func setNetworkStatus(_ text: String, isError: Bool) {
        networkStatus = text
        if isError { errorMessage = text }
        notify()
    }

    func finishIncomingTransfer(itemCount: Int) {
        networkStatus = "局域网接收已开启，等待电脑自动发现"
        message = "已接收 \(itemCount) 个文件，作品列表已刷新"
        refresh(showConfirmation: false)
    }

    private func activate(_ url: URL, securityScoped: Bool) throws {
        guard !isHiddenDirectory(url) else { throw LibraryError.hiddenFolder }
        guard FileManager.default.fileExists(atPath: url.path) else { throw LibraryError.folderUnavailable }
        let scoped = securityScoped ? url.startAccessingSecurityScopedResource() : false
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
        rootURL = url
        hasSecurityScope = scoped
        folderName = url.lastPathComponent
    }

    private func saveBookmark(_ url: URL) throws {
        let data = try url.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil,
                                        relativeTo: nil)
        UserDefaults.standard.set(data, forKey: bookmarkKey)
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
            let original = record.originalRelativePath ?? key
            return TrashItem(key: key, name: URL(fileURLWithPath: original).lastPathComponent,
                             originalRelativePath: original, folderURL: folder,
                             shareCount: record.shareCount,
                             trashedDate: record.trashedDate.flatMap { Self.dayFormatter.date(from: $0) })
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private func childDirectories(of url: URL, includeHidden: Bool = false) throws -> [URL] {
        let directories = try FileManager.default.contentsOfDirectory(
            at: url, includingPropertiesForKeys: [.isDirectoryKey, .isHiddenKey],
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
            if !FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.moveItem(at: child, to: destination)
            }
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
                  let lastDate = Self.dayFormatter.date(from: lastText), lastDate < today else { continue }
            let original = record.originalRelativePath ?? key
            let source = url(forRelativePath: original, under: root)
            guard FileManager.default.fileExists(atPath: source.path) else { continue }
            try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
            let trashFolder = record.trashFolderName ?? "\(source.lastPathComponent)-\(UUID().uuidString.prefix(8))"
            let destination = trashURL.appendingPathComponent(trashFolder, isDirectory: true)
            guard !FileManager.default.fileExists(atPath: destination.path) else { continue }
            try FileManager.default.moveItem(at: source, to: destination)
            var changed = record
            changed.trashedDate = todayText
            changed.originalRelativePath = original
            changed.trashFolderName = trashFolder
            state.works[key] = changed
            do { try saveState(to: root) }
            catch {
                state.works[key] = record
                try? FileManager.default.moveItem(at: destination, to: source)
                throw error
            }
        }
        for (key, record) in Array(state.works) {
            guard let text = record.trashedDate, let date = Self.dayFormatter.date(from: text),
                  let expiry = Calendar.current.date(byAdding: .day, value: 7, to: date), expiry <= today else { continue }
            let folder = trashURL.appendingPathComponent(record.trashFolderName ??
                URL(fileURLWithPath: key).lastPathComponent, isDirectory: true)
            if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
            state.works.removeValue(forKey: key)
            try saveState(to: root)
        }
    }

    private func url(forRelativePath path: String, under root: URL) -> URL {
        return path.split(separator: "/").reduce(root) {
            $0.appendingPathComponent(String($1), isDirectory: true)
        }
    }

    private func loadState(from root: URL) throws -> LibraryState {
        let url = root.appendingPathComponent(stateName)
        guard FileManager.default.fileExists(atPath: url.path) else { return LibraryState() }
        do { return try JSONDecoder().decode(LibraryState.self, from: Data(contentsOf: url)) }
        catch {
            let backup = root.appendingPathComponent("_相册状态损坏-\(Self.backupFormatter.string(from: Date())).json")
            try FileManager.default.moveItem(at: url, to: backup)
            message = "分享记录已重建，作品文件没有删除。"
            return LibraryState()
        }
    }

    private func saveState(to root: URL) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(state).write(to: root.appendingPathComponent(stateName), options: .atomic)
    }

    private func report(_ error: Error) {
        errorMessage = (error as? LocalizedError)?.errorDescription ?? "操作失败：\(error.localizedDescription)"
        notify()
    }

    private func notify() { onChange?() }

    private static func documentsURL() throws -> URL {
        guard let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            throw LibraryError.folderUnavailable
        }
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
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
