import Foundation
import UIKit

struct ManagedFolderChoice {
    let title: String
    let url: URL
}

final class WorkLibrary {
    static let advertisedWorkCountKey = "album.advertisedWorkCount.v1"
    static let advertisedWorkConversionCountKey = "album.advertisedWorkConversionCount.v1"
    static let advertisedWorkTrafficCountKey = "album.advertisedWorkTrafficCount.v1"
    static let advertisedWorkUncategorizedCountKey = "album.advertisedWorkUncategorizedCount.v1"
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
    private let managedFolderKey = "album.managedFolderRelativePath.v1"
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
        if usesManagedFolder || !supportsExternalFolderSelection {
            let relative = UserDefaults.standard.string(forKey: managedFolderKey) ?? ""
            return relative.isEmpty ? "我的 iPhone/相册" : "我的 iPhone/相册/\(relative)"
        }
        return folderName ?? "未选择"
    }

    var receivingRootURL: URL? { return rootURL }
    var advertisedWorkCount: Int { UserDefaults.standard.integer(forKey: Self.advertisedWorkCountKey) }
    var advertisedWorkCounts: [String: Int]? {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: Self.advertisedWorkConversionCountKey) != nil,
              defaults.object(forKey: Self.advertisedWorkTrafficCountKey) != nil,
              defaults.object(forKey: Self.advertisedWorkUncategorizedCountKey) != nil else {
            return nil
        }
        let conversion = defaults.integer(forKey: Self.advertisedWorkConversionCountKey)
        let traffic = defaults.integer(forKey: Self.advertisedWorkTrafficCountKey)
        let uncategorized = defaults.integer(forKey: Self.advertisedWorkUncategorizedCountKey)
        return [
            "total": conversion + traffic + uncategorized,
            "conversion": conversion,
            "traffic": traffic,
            "uncategorized": uncategorized
        ]
    }

    deinit {
        if hasSecurityScope { rootURL?.stopAccessingSecurityScopedResource() }
    }

    func start() {
        if !supportsExternalFolderSelection {
            do {
                let documents = try Self.documentsURL()
                let relative = UserDefaults.standard.string(forKey: managedFolderKey) ?? ""
                let selected = relative.isEmpty ? documents : documents.appendingPathComponent(relative, isDirectory: true)
                let root = FileManager.default.fileExists(atPath: selected.path) ? selected : documents
                if root == documents { UserDefaults.standard.removeObject(forKey: managedFolderKey) }
                try activate(root, securityScoped: false)
                usesManagedFolder = true
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
            UserDefaults.standard.removeObject(forKey: managedFolderKey)
            refresh(showConfirmation: showConfirmation)
        } catch { report(error) }
    }

    func managedFolderChoices() -> [ManagedFolderChoice] {
        guard let documents = try? Self.documentsURL() else { return [] }
        var result = [ManagedFolderChoice(title: "全部接收内容", url: documents)]
        var visited = 0

        func visit(_ folder: URL, relative: [String], depth: Int) {
            guard depth < 6, visited < 200 else { return }
            let entries = (try? FileManager.default.contentsOfDirectory(
                at: folder, includingPropertiesForKeys: [.isDirectoryKey, .isHiddenKey],
                options: [.skipsPackageDescendants])) ?? []
            for child in NaturalSort.urls(entries) {
                guard (try? child.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
                let name = child.lastPathComponent
                if name.hasPrefix(".") || isTrashDirectoryName(name) { continue }
                let parts = relative + [name]
                result.append(ManagedFolderChoice(title: parts.joined(separator: "/"), url: child))
                visited += 1
                visit(child, relative: parts, depth: depth + 1)
            }
        }

        visit(documents, relative: [], depth: 0)
        return result
    }

    private func isTrashDirectoryName(_ name: String) -> Bool {
        for base in [trashName, legacyTrashName] {
            if name == base { return true }
            guard name.hasPrefix(base + " ("), name.hasSuffix(")") else { continue }
            let start = name.index(name.startIndex, offsetBy: base.count + 2)
            let number = name[start..<name.index(before: name.endIndex)]
            if !number.isEmpty && number.allSatisfy(\.isNumber) { return true }
        }
        return false
    }

    func selectManagedFolder(_ url: URL) {
        do {
            let documents = try Self.documentsURL().standardizedFileURL
            let selected = url.standardizedFileURL
            let documentsPath = documents.path.hasSuffix("/") ? documents.path : documents.path + "/"
            guard selected.path == documents.path || selected.path.hasPrefix(documentsPath) else {
                throw LibraryError.folderUnavailable
            }
            var relative = String(selected.path.dropFirst(documents.path.count))
            if relative.hasPrefix("/") { relative.removeFirst() }
            try activate(selected, securityScoped: false)
            usesManagedFolder = true
            UserDefaults.standard.removeObject(forKey: bookmarkKey)
            if relative.isEmpty { UserDefaults.standard.removeObject(forKey: managedFolderKey) }
            else { UserDefaults.standard.set(relative, forKey: managedFolderKey) }
            refresh(showConfirmation: true)
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

    func importItems(_ urls: [URL]) {
        guard let root = rootURL else { report(LibraryError.noFolder); return }
        guard !urls.isEmpty else { return }
        isBusy = true
        notify()
        defer { isBusy = false; notify() }
        do {
            var copied = 0
            var extracted = 0
            var batchFolder: URL?
            for source in urls {
                let accessing = source.startAccessingSecurityScopedResource()
                defer { if accessing { source.stopAccessingSecurityScopedResource() } }
                if source.pathExtension.lowercased() == "zip" {
                    extracted += try StoredZipExtractor.extract(source, to: root)
                    continue
                }
                if batchFolder == nil {
                    let formatter = DateFormatter()
                    formatter.dateFormat = "yyyyMMdd-HHmmss"
                    let name = urls.count > 1 ? "导入作品-\(formatter.string(from: Date()))" : "导入文件"
                    batchFolder = StoredZipExtractor.uniqueDestination(for: name, under: root)
                    try FileManager.default.createDirectory(at: batchFolder!, withIntermediateDirectories: true)
                }
                let destination = StoredZipExtractor.uniqueDestination(
                    for: source.lastPathComponent, under: batchFolder!)
                try FileManager.default.copyItem(at: source, to: destination)
                copied += 1
            }
            message = extracted > 0
                ? "导入完成：\(copied) 个文件，解压 \(extracted) 个文件"
                : "已导入 \(copied) 个文件"
            refresh(showConfirmation: false)
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
            let now = Date()
            try migrateLegacyCleanupTimestamps(root: root, now: now)
            try performMaintenance(root: root, now: now)
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
        return try prepareShare(work, images: work.imageURLs, platform: .xhs)
    }

    func prepareShare(_ work: WorkItem, images: [URL]) throws -> [Any] {
        return try prepareShare(work, images: images, platform: .xhs)
    }

    func prepareShare(_ work: WorkItem, images: [URL], platform: CopyPlatform) throws -> [Any] {
        let text: String
        do { text = try String(contentsOf: work.textURL, encoding: .utf8) }
        catch { throw LibraryError.unreadableCopy }
        let parsed = PlatformCopyParser.parse(text, platform: platform)
        switch parsed.status {
        case .missing: throw LibraryError.missingPlatformCopy(platform.displayName)
        case .unreadable: throw LibraryError.unreadableCopy
        case .ok: break
        }
        let allowed = Set(work.imageURLs.map { $0.standardizedFileURL })
        let selected = images.map { $0.standardizedFileURL }.filter { allowed.contains($0) }
        guard !selected.isEmpty else { throw LibraryError.noImages(work.name) }

        var record = state.works[work.key] ?? state.history[work.key] ?? WorkState()
        state.history.removeValue(forKey: work.key)
        record.shareCount += 1
        record.used = true
        if platform == .xhs { record.xhsShareCount += 1 }
        if platform == .douyin { record.douyinShareCount += 1 }
        let now = Date()
        record.lastShareDate = Self.dayFormatter.string(from: now)
        if record.firstSharedAtMs == nil {
            record.firstSharedAtMs = now.timeIntervalSince1970 * 1000
        }
        if record.firstUsedAtMs == nil { record.firstUsedAtMs = record.firstSharedAtMs }
        if record.deleteScheduledAtMs == nil {
            record.deleteScheduledAtMs = (record.firstUsedAtMs ?? now.timeIntervalSince1970 * 1000)
                + Double(CleanupPreferences.deleteHours) * 3_600_000
        }
        record.trashedDate = nil
        record.trashedAtMs = nil
        record.originalRelativePath = work.relativePath
        state.works[work.key] = record
        guard let root = rootURL else { throw LibraryError.noFolder }
        do { try saveState(to: root) }
        catch { throw LibraryError.stateWriteFailed }

        UIPasteboard.general.string = parsed.text
        message = "文案已复制 · 第 \(record.shareCount) 次打开分享"
        works = (try? scanner.scan(root: root, state: state).works) ?? works
        notify()
        return selected.map { $0 as NSURL }
    }

    func moveImagesToTrash(_ work: WorkItem, images: [URL]) throws -> Int {
        let allowed = Set(work.imageURLs.map { $0.standardizedFileURL })
        let selected = images.map { $0.standardizedFileURL }.filter { allowed.contains($0) }
        guard !selected.isEmpty else { return 0 }
        let formatter = DateFormatter(); formatter.dateFormat = "yyyy-MM-dd"
        let bin = work.folderURL.appendingPathComponent(".图片回收站", isDirectory: true)
            .appendingPathComponent(formatter.string(from: Date()), isDirectory: true)
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        var moved = 0
        for source in selected {
            let destination = StoredZipExtractor.uniqueDestination(for: source.lastPathComponent, under: bin)
            try FileManager.default.moveItem(at: source, to: destination)
            moved += 1
        }
        refresh(showConfirmation: false)
        return moved
    }

    func moveWorkToTrash(_ work: WorkItem) throws {
        guard let root = rootURL else { throw LibraryError.noFolder }
        let source = work.folderURL.standardizedFileURL
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw LibraryError.operationFailed("作品文件夹不存在，未删除任何内容。")
        }
        let now = Date()
        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)
        try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
        let destination = StoredZipExtractor.uniqueDestination(
            for: "\(source.lastPathComponent)-\(UUID().uuidString.prefix(8))", under: trashURL)
        try FileManager.default.moveItem(at: source, to: destination)

        let previous = state.works[work.key] ?? WorkState()
        var record = previous
        record.trashedDate = Self.dayFormatter.string(from: now)
        record.trashedAtMs = now.timeIntervalSince1970 * 1000
        record.originalRelativePath = work.relativePath
        record.trashFolderName = destination.lastPathComponent
        if record.firstSharedAtMs == nil && record.firstUsedAtMs == nil {
            record.firstSharedAtMs = record.trashedAtMs
        }
        state.works[work.key] = record
        do {
            try saveState(to: root)
        } catch {
            state.works[work.key] = previous
            try? FileManager.default.moveItem(at: destination, to: source)
            throw error
        }
        message = "已移到回收站：\(work.name)"
        refresh(showConfirmation: false)
    }

    func imageTrashCount(_ work: WorkItem) -> Int {
        let bin = work.folderURL.appendingPathComponent(".图片回收站", isDirectory: true)
        guard let enumerator = FileManager.default.enumerator(at: bin, includingPropertiesForKeys: [.isRegularFileKey]) else { return 0 }
        var count = 0
        for case let url as URL in enumerator where (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true { count += 1 }
        return count
    }

    func restoreAllImages(_ work: WorkItem) throws -> Int {
        let bin = work.folderURL.appendingPathComponent(".图片回收站", isDirectory: true)
        guard let enumerator = FileManager.default.enumerator(at: bin, includingPropertiesForKeys: [.isRegularFileKey]) else { return 0 }
        let files = enumerator.compactMap { $0 as? URL }.filter {
            (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
        }
        for source in files {
            let destination = StoredZipExtractor.uniqueDestination(for: source.lastPathComponent, under: work.folderURL)
            try FileManager.default.moveItem(at: source, to: destination)
        }
        try? FileManager.default.removeItem(at: bin)
        refresh(showConfirmation: false)
        return files.count
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
            record.firstSharedAtMs = nil
            record.trashedAtMs = nil
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
        state.works = state.works.filter {
            $0.value.trashedDate == nil && $0.value.trashFolderName == nil
        }
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
        networkStatus = "接收已开启，请保持相册在前台"
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
                guard entry.value.trashedDate != nil || entry.value.trashFolderName != nil else { return false }
                return entry.value.trashFolderName == folder.lastPathComponent ||
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

    private func migrateLegacyCleanupTimestamps(root: URL, now: Date) throws {
        var changed = false
        for (key, original) in Array(state.works) {
            var record = original
            let legacyText = record.lastShareDate ?? record.trashedDate
            if record.firstSharedAtMs == nil, let text = legacyText {
                record.firstSharedAtMs = CleanupPolicy.legacyAnchor(dateText: text, now: now)
            }
            if record.firstUsedAtMs == nil { record.firstUsedAtMs = record.firstSharedAtMs }
            if record.deleteScheduledAtMs == nil, let first = record.firstUsedAtMs {
                record.deleteScheduledAtMs = first + Double(CleanupPreferences.deleteHours) * 3_600_000
            }
            if record.trashedDate != nil, record.trashedAtMs == nil {
                record.trashedAtMs = CleanupPolicy.legacyAnchor(
                    dateText: record.trashedDate!, now: now)
            }
            if record.firstSharedAtMs != original.firstSharedAtMs ||
                record.firstUsedAtMs != original.firstUsedAtMs ||
                record.deleteScheduledAtMs != original.deleteScheduledAtMs ||
                record.trashedAtMs != original.trashedAtMs {
                state.works[key] = record
                changed = true
            }
        }

        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)
        if FileManager.default.fileExists(atPath: trashURL.path) {
            for folder in try childDirectories(of: trashURL, includeHidden: true) {
                let tracked = state.works.contains { entry in
                    (entry.value.trashedDate != nil || entry.value.trashFolderName != nil) &&
                    (entry.value.trashFolderName == folder.lastPathComponent ||
                     (entry.value.trashFolderName == nil && entry.key == folder.lastPathComponent))
                }
                if tracked { continue }
                var key = "legacy-trash-\(folder.lastPathComponent)"
                while state.works[key] != nil { key += "-\(UUID().uuidString.prefix(4))" }
                var record = WorkState()
                record.originalRelativePath = folder.lastPathComponent
                record.trashFolderName = folder.lastPathComponent
                record.trashedDate = Self.dayFormatter.string(from: now)
                record.firstSharedAtMs = now.timeIntervalSince1970 * 1000
                record.trashedAtMs = record.firstSharedAtMs
                state.works[key] = record
                changed = true
            }
        }
        if state.schemaVersion != 4 {
            state.schemaVersion = 4
            changed = true
        }
        if changed { try saveState(to: root) }
    }

    private func performMaintenance(root: URL, now: Date) throws {
        let todayText = Self.dayFormatter.string(from: now)
        let trashURL = root.appendingPathComponent(trashName, isDirectory: true)
        for (key, record) in Array(state.works) where record.trashedDate == nil {
            guard CleanupPolicy.isDue(anchorMilliseconds: record.firstSharedAtMs,
                                      hours: CleanupPreferences.moveHours, now: now) else { continue }
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
            changed.trashedAtMs = now.timeIntervalSince1970 * 1000
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
            guard record.trashedDate != nil || record.trashFolderName != nil,
                  CleanupPolicy.isDue(anchorMilliseconds: record.deleteScheduledAtMs
                        ?? record.firstSharedAtMs ?? record.trashedAtMs,
                        hours: record.deleteScheduledAtMs == nil ? CleanupPreferences.deleteHours : 0,
                        now: now) else { continue }
            let folder = trashURL.appendingPathComponent(record.trashFolderName ??
                URL(fileURLWithPath: key).lastPathComponent, isDirectory: true)
            if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
            state.history[key] = record
            state.works.removeValue(forKey: key)
            try saveState(to: root)
        }
        try purgeExpiredImageTrash(root: root, today: now)
    }

    private func purgeExpiredImageTrash(root: URL, today: Date) throws {
        let keys: Set<URLResourceKey> = [.isDirectoryKey]
        guard let enumerator = FileManager.default.enumerator(at: root,
                                                               includingPropertiesForKeys: Array(keys),
                                                               options: []) else { return }
        let bins = enumerator.compactMap { $0 as? URL }.filter { url in
            guard url.lastPathComponent == ".图片回收站" else { return false }
            return (try? url.resourceValues(forKeys: keys).isDirectory) == true
        }
        for bin in bins {
            for dayFolder in try childDirectories(of: bin, includeHidden: true) {
                guard let date = Self.dayFormatter.date(from: dayFolder.lastPathComponent),
                      let expiry = Calendar.current.date(byAdding: .day, value: 7, to: date),
                      expiry <= today else { continue }
                try FileManager.default.removeItem(at: dayFolder)
            }
            if (try FileManager.default.contentsOfDirectory(atPath: bin.path)).isEmpty {
                try FileManager.default.removeItem(at: bin)
            }
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

    private func notify() {
        UserDefaults.standard.set(works.count, forKey: Self.advertisedWorkCountKey)
        UserDefaults.standard.set(works.filter { $0.category == WorkCategory.conversion }.count,
                                  forKey: Self.advertisedWorkConversionCountKey)
        UserDefaults.standard.set(works.filter { $0.category == WorkCategory.traffic }.count,
                                  forKey: Self.advertisedWorkTrafficCountKey)
        UserDefaults.standard.set(works.filter { $0.category == WorkCategory.uncategorized }.count,
                                  forKey: Self.advertisedWorkUncategorizedCountKey)
        onChange?()
    }

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
        value.timeZone = CleanupPolicy.beijingTimeZone
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
