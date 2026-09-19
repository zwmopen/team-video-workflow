import Foundation

public final class OnlineWorkLifecycle {
    private static let prefName = "online_work_lifecycle_records"
    private static let lock = NSLock()

    public struct Item: Codable, Hashable {
        public let id: String
        public let title: String
        public let destination: String
        public var firstSharedAtMs: Double
        public var trashedAtMs: Double
        public var useCount: Int
        public let images: [String]
        public let copyText: String

        public init(id: String, title: String, destination: String,
                    firstSharedAtMs: Double, trashedAtMs: Double, useCount: Int,
                    images: [String], copyText: String) {
            self.id = id
            self.title = title
            self.destination = destination
            self.firstSharedAtMs = firstSharedAtMs
            self.trashedAtMs = trashedAtMs
            self.useCount = useCount
            self.images = images
            self.copyText = copyText
        }
    }

    private init() {}

    private static func loadAll() -> [Item] {
        guard let data = UserDefaults.standard.data(forKey: prefName),
              let list = try? JSONDecoder().decode([Item].self, from: data) else {
            return []
        }
        return list
    }

    private static func saveAll(_ list: [Item]) {
        if let data = try? JSONEncoder().encode(list) {
            UserDefaults.standard.set(data, forKey: prefName)
        }
    }

    @discardableResult
    public static func markUsed(work: OnlineWorkEntry, nowMs: Double = Date().timeIntervalSince1970 * 1000) -> Item {
        lock.lock()
        defer { lock.unlock() }
        var list = loadAll()
        if let index = list.firstIndex(where: { $0.id == work.id }) {
            var item = list[index]
            if item.firstSharedAtMs <= 0 {
                item.firstSharedAtMs = nowMs
            }
            item.useCount += 1
            list[index] = item
            saveAll(list)
            return item
        } else {
            let newItem = Item(id: work.id, title: work.title, destination: work.destination,
                               firstSharedAtMs: nowMs, trashedAtMs: 0,
                               useCount: max(1, work.useCount + 1),
                               images: work.images, copyText: work.copyText)
            list.append(newItem)
            saveAll(list)
            return newItem
        }
    }

    @discardableResult
    public static func moveToTrash(work: OnlineWorkEntry, nowMs: Double = Date().timeIntervalSince1970 * 1000) -> Item {
        lock.lock()
        defer { lock.unlock() }
        var list = loadAll()
        if let index = list.firstIndex(where: { $0.id == work.id }) {
            var item = list[index]
            if item.firstSharedAtMs <= 0 {
                item.firstSharedAtMs = nowMs
            }
            item.trashedAtMs = nowMs
            list[index] = item
            saveAll(list)
            return item
        } else {
            let newItem = Item(id: work.id, title: work.title, destination: work.destination,
                               firstSharedAtMs: nowMs, trashedAtMs: nowMs,
                               useCount: work.useCount,
                               images: work.images, copyText: work.copyText)
            list.append(newItem)
            saveAll(list)
            return newItem
        }
    }

    public static func getRecord(id: String) -> Item? {
        lock.lock()
        defer { lock.unlock() }
        return loadAll().first(where: { $0.id == id })
    }

    public static func getTrashItems(nowMs: Double = Date().timeIntervalSince1970 * 1000,
                                     moveAfterMs: Double = 3600 * 1000,
                                     deleteAfterMs: Double = 24 * 3600 * 1000) -> [Item] {
        lock.lock()
        defer { lock.unlock() }
        var list = loadAll()
        var changed = false
        var activeTrash: [Item] = []

        var filtered: [Item] = []
        for var item in list {
            // 检查自动超时移入回收站
            if item.trashedAtMs <= 0 && item.firstSharedAtMs > 0 && (nowMs - item.firstSharedAtMs >= moveAfterMs) {
                item.trashedAtMs = nowMs
                changed = true
            }
            // 检查自动超时彻底删除
            if item.trashedAtMs > 0 && (nowMs - item.trashedAtMs >= deleteAfterMs) {
                changed = true
                continue // 彻底移除
            }
            filtered.append(item)
            if item.trashedAtMs > 0 {
                activeTrash.append(item)
            }
        }
        if changed {
            saveAll(filtered)
        }
        // 按 trashedAt 降序排
        return activeTrash.sorted(by: { $0.trashedAtMs > $1.trashedAtMs })
    }

    public static func restoreFromTrash(id: String) {
        lock.lock()
        defer { lock.unlock() }
        var list = loadAll()
        if let idx = list.firstIndex(where: { $0.id == id }) {
            var item = list[idx]
            item.trashedAtMs = 0
            // 恢复后重置首次使用时间，避免立即再次被回收
            item.firstSharedAtMs = 0
            list[idx] = item
            saveAll(list)
        }
    }

    public static func deletePermanently(id: String) {
        lock.lock()
        defer { lock.unlock() }
        var list = loadAll()
        list.removeAll(where: { $0.id == id })
        saveAll(list)
    }

    public static func clearTrash() {
        lock.lock()
        defer { lock.unlock() }
        var list = loadAll()
        list.removeAll(where: { $0.trashedAtMs > 0 })
        saveAll(list)
    }

    public static func filterActiveOnlineWorks(works: [OnlineWorkEntry],
                                               nowMs: Double = Date().timeIntervalSince1970 * 1000,
                                               moveAfterMs: Double = 3600 * 1000) -> [OnlineWorkEntry] {
        lock.lock()
        let list = loadAll()
        lock.unlock()

        var trashedIds = Set<String>()
        for item in list {
            if item.trashedAtMs > 0 {
                trashedIds.insert(item.id)
            } else if item.firstSharedAtMs > 0 && (nowMs - item.firstSharedAtMs >= moveAfterMs) {
                trashedIds.insert(item.id)
            }
        }
        return works.filter { !trashedIds.contains($0.id) }
    }
}
