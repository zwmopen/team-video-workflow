import Foundation

/// 在线相册列表的本地快照（iOS 版，对应 Android `OnlineListCache.java`）。
///
/// 存在理由（2026-09-20 与 Android 同源实测）：
/// 电脑端 `/api/online/works` 全量响应裸发 2.75 MB，瘦身 + gzip 后 ~420 KB。
/// 冷启动后第一次点进在线相册，无论如何都要等一次网络往返。
/// 而「点进在线相册」是用户必然动作 —— 把上一次成功的列表落盘：
/// 下次进页面先渲染快照、再后台刷新，体感从「等网络」变成「秒开」。
///
/// 设计约束（与 Android 严格对齐）：
/// 1. **只做缓存，不做判定**。原样存服务端返回的 JSON Data，不解析、不改写字段，
///    避免与 `OnlineWorkEntry.from(dict:)` 的字段演进脱节。
/// 2. **原子写**：先写 `.tmp` 再 rename，杜绝进程被杀时留下半截 JSON。
/// 3. **有保质期**：超过 `maxAgeMs`（7 天）的快照不再使用，宁可显示「正在连接」也不要错内容。
public enum OnlineListCache {
    public static let maxAgeMs: Int64 = 7 * 24 * 3600 * 1000

    private static let dirName = "online_cache"
    private static let fileWorks = "last_works.json"
    private static let fileCategories = "last_categories.json"

    // MARK: - 路径

    /// Documents/online_cache/（每次启动创建一次，无需每次判断）
    private static func cacheDir() -> URL? {
        guard let docs = FileManager.default.urls(for: .documentDirectory,
                                                  in: .userDomainMask).first
        else { return nil }
        let dir = docs.appendingPathComponent(dirName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir,
                                                    withIntermediateDirectories: true)
        }
        return dir
    }

    // MARK: - works

    /// 保存全量作品列表快照（仅「全量列表」请求才值得做快照，见 Android 注释）。
    /// - Parameter data: URLSession 已经透明解压后的 JSON Data。
    public static func saveWorks(_ data: Data) {
        write(named: fileWorks, data: data)
    }

    /// 读回上次成功的作品列表 JSON；不存在 / 过期 / 读失败时返回 `nil`。
    /// **不解析** —— 由调用方用 `OnlineWorkEntry.from(dict:)` 判定可用性。
    public static func loadWorks() -> Data? {
        return read(named: fileWorks)
    }

    public static func snapshotAtMs() -> Int64 {
        guard let dir = cacheDir() else { return 0 }
        let path = dir.appendingPathComponent(fileWorks).path
        guard FileManager.default.fileExists(atPath: path) else { return 0 }
        let attrs = try? FileManager.default.attributesOfItem(atPath: path)
        let mtime = (attrs?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return Int64(mtime * 1000)
    }

    // MARK: - categories

    public static func saveCategories(_ data: Data) {
        write(named: fileCategories, data: data)
    }

    public static func loadCategories() -> Data? {
        return read(named: fileCategories)
    }

    // MARK: - 维护

    /// 清空快照：解析失败时由调用方主动调用，避免下次冷启动又白解析一次坏文件。
    public static func clear() {
        guard let dir = cacheDir() else { return }
        if let kids = try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: nil) {
            for url in kids {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    // MARK: - 内部

    private static func write(named name: String, data: Data) {
        guard !data.isEmpty, let dir = cacheDir() else { return }
        let final = dir.appendingPathComponent(name)
        let tmp = final.appendingPathExtension("tmp")
        do {
            try data.write(to: tmp, options: [.atomic])
        } catch {
            NSLog("[OnlineListCache] 快照写入失败: %@", String(describing: error))
            return
        }
        // rename 在 iOS 上是原子的；个别沙盒偶发失败，退化为「删 + 改名」
        if !FileManager.default.fileExists(atPath: final.path) {
            do { try FileManager.default.moveItem(at: tmp, to: final) }
            catch { NSLog("[OnlineListCache] 快照改名失败: %@", String(describing: error)) }
            return
        }
        do { try FileManager.default.removeItem(at: final) }
        catch { NSLog("[OnlineListCache] 旧快照删除失败: %@", String(describing: error)) }
        do { try FileManager.default.moveItem(at: tmp, to: final) }
        catch {
            NSLog("[OnlineListCache] 快照改名失败: %@", String(describing: error))
            try? FileManager.default.removeItem(at: tmp)
        }
    }

    private static func read(named name: String) -> Data? {
        guard let dir = cacheDir() else { return nil }
        let url = dir.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let mtime = (try? FileManager.default.attributesOfItem(atPath: url.path))?
            [.modificationDate] as? Date
        if let m = mtime {
            let ageMs = Int64(Date().timeIntervalSince(m) * 1000)
            if ageMs > maxAgeMs {
                NSLog("[OnlineListCache] 快照已过期（%lld 小时），忽略", ageMs / 3600000)
                return nil
            }
        }
        return try? Data(contentsOf: url)
    }
}