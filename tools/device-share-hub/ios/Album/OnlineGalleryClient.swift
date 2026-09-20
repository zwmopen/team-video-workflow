import UIKit

public struct OnlineCategoryItem: Codable, Hashable {
    public let name: String
    public let count: Int
}

public struct OnlineCategoriesResult: Codable {
    public let categories: [OnlineCategoryItem]
    public let stages: [OnlineCategoryItem]
    public let total: Int
}

public struct OnlineWorkEntry: Identifiable, Hashable {
    public let id: String
    public let title: String
    public let destination: String
    public let stage: String
    public let useCount: Int
    public let maxUses: Int
    public let used: Bool
    public let remainingUses: Int
    public let statusLabel: String
    public let images: [String]
    public let imageCount: Int
    public let copyText: String
    public let hasCopyText: Bool
    public let dispatchedTo: [String]
    public let updatedAt: Double

    public init(id: String, title: String, destination: String, stage: String,
                useCount: Int, maxUses: Int, used: Bool, remainingUses: Int,
                statusLabel: String, images: [String], imageCount: Int,
                copyText: String, hasCopyText: Bool, dispatchedTo: [String],
                updatedAt: Double) {
        self.id = id
        self.title = title.isEmpty ? id : title
        self.destination = destination.isEmpty ? "其他" : destination
        self.stage = stage
        self.useCount = useCount
        self.maxUses = maxUses <= 0 ? 2 : maxUses
        self.used = used || useCount > 0
        self.remainingUses = max(0, remainingUses)
        self.statusLabel = statusLabel
        self.images = images
        self.imageCount = imageCount > 0 ? imageCount : images.count
        self.copyText = copyText
        self.hasCopyText = hasCopyText || !copyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        self.dispatchedTo = dispatchedTo
        self.updatedAt = updatedAt
    }

    public static func from(dict: [String: Any]) -> OnlineWorkEntry? {
        guard let id = dict["id"] as? String, !id.isEmpty else { return nil }
        let title = (dict["title"] as? String) ?? id
        let destination = (dict["destination"] as? String) ?? "其他"
        let stage = (dict["stage"] as? String) ?? ""
        let useCount = (dict["useCount"] as? Int) ?? 0
        let maxUses = (dict["maxUses"] as? Int) ?? 2
        let used = (dict["used"] as? Bool) ?? (useCount > 0)
        let remainingUses = (dict["remainingUses"] as? Int) ?? max(0, maxUses - useCount)
        let statusLabel = (dict["statusLabel"] as? String) ?? ""
        let images = (dict["images"] as? [String]) ?? []
        let imageCount = (dict["imageCount"] as? Int) ?? images.count
        let copyText = (dict["copyText"] as? String) ?? ""
        let hasCopyText = (dict["hasCopyText"] as? Bool) ?? !copyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let dispatchedTo = (dict["dispatchedTo"] as? [String]) ?? []
        let updatedAt = (dict["updatedAt"] as? Double) ?? Date().timeIntervalSince1970 * 1000

        return OnlineWorkEntry(
            id: id, title: title, destination: destination, stage: stage,
            useCount: useCount, maxUses: maxUses, used: used, remainingUses: remainingUses,
            statusLabel: statusLabel, images: images, imageCount: imageCount,
            copyText: copyText, hasCopyText: hasCopyText, dispatchedTo: dispatchedTo,
            updatedAt: updatedAt
        )
    }
}

public struct OnlineUseResult {
    public let ok: Bool
    public let workId: String
    public let useCount: Int
    public let remainingUses: Int
    public let moved: Bool
    public let message: String
}

public final class OnlineGalleryClient {
    public static let shared = OnlineGalleryClient()
    public static let defaultPort = 45835
    private let prefs = UserDefaults.standard
    private let prefCustomUrlKey = "customPcServerUrl"
    /// 局域网信标：电脑端每 2 秒广播一次自身地址
    static let beaconPort: UInt16 = 45832
    static let prefManualUrlKey = "manualPcServerUrl"
    static let prefBeaconUrlKey = "beaconPcServerUrl"
    static let prefBeaconAtKey = "beaconPcServerAtMs"
    static let prefLastGoodKey = "lastGoodPcServerUrl"
    /// 信标地址保鲜期：超过该时长视为陈旧
    static let beaconFreshMs: Double = 5 * 60 * 1000
    private var cachedBaseUrl: String?

    private let session: URLSession
    private let imageCache = NSCache<NSString, UIImage>()
    private let fileManager = FileManager.default
    private lazy var diskCacheURL: URL = {
        let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("OnlineGalleryImageCache", isDirectory: true)
        try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        self.session = URLSession(configuration: config)
        imageCache.countLimit = 300
        imageCache.totalCostLimit = 60 * 1024 * 1024 // 60MB 内存缓存
    }

    /// 回环地址：只有存在 USB / ADB 转发隧道时才可达，纯 Wi-Fi 下必然失败
    public static func isLoopback(_ url: String) -> Bool {
        url.contains("127.0.0.1") || url.contains("localhost")
    }

    /// 地址选路优先级：
    /// 1) 用户手填或已自动发现的局域网地址（非回环才认）
    /// 2) 局域网信标地址（电脑主动广播，5 分钟内新鲜）
    /// 3) 最近一次成功连通的地址
    /// 4) 回环兜底（仅 USB 隧道有效）
    /// 修复：旧版把电脑 IP 写死成 192.168.0.106，换网络后 iPhone 永远读不到在线相册。
    public func resolveBaseUrl() -> String {
        if let cached = cachedBaseUrl, !cached.isEmpty {
            return cached
        }
        // 1) 用户在设置里手工填写的地址：优先级最高
        if let manual = prefs.string(forKey: Self.prefManualUrlKey)?.trimmingCharacters(in: .whitespaces),
           !manual.isEmpty, !Self.isLoopback(manual) {
            cachedBaseUrl = manual
            return manual
        }
        // 2) 局域网信标地址（5 分钟内新鲜）
        if let beacon = prefs.string(forKey: Self.prefBeaconUrlKey)?.trimmingCharacters(in: .whitespaces),
           !beacon.isEmpty {
            let at = prefs.double(forKey: Self.prefBeaconAtKey)
            let nowMs = Date().timeIntervalSince1970 * 1000
            if at > 0, nowMs - at <= Self.beaconFreshMs {
                cachedBaseUrl = beacon
                return beacon
            }
        }
        // 3) 自动发现 / 上次成功过的地址
        if let custom = prefs.string(forKey: prefCustomUrlKey)?.trimmingCharacters(in: .whitespaces),
           !custom.isEmpty, !Self.isLoopback(custom) {
            cachedBaseUrl = custom
            return custom
        }
        if let lastGood = prefs.string(forKey: Self.prefLastGoodKey)?.trimmingCharacters(in: .whitespaces),
           !lastGood.isEmpty, !Self.isLoopback(lastGood) {
            cachedBaseUrl = lastGood
            return lastGood
        }
        // 4) 最后兜底：回环（仅 USB 隧道有效）
        let loopback = "http://127.0.0.1:\(Self.defaultPort)"
        cachedBaseUrl = loopback
        return loopback
    }

    /// 自动发现命中的地址（允许被更新的信标覆盖）
    public func setCustomBaseUrl(_ urlString: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespaces)
        prefs.set(trimmed, forKey: prefCustomUrlKey)
        cachedBaseUrl = trimmed.isEmpty ? nil : trimmed
    }

    /// 用户在设置里手工指定的地址：视为明确意图，不被自动发现覆盖
    public func setManualBaseUrl(_ urlString: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespaces)
        prefs.set(trimmed, forKey: Self.prefManualUrlKey)
        prefs.set(trimmed, forKey: prefCustomUrlKey)
        cachedBaseUrl = trimmed.isEmpty ? nil : trimmed
    }

    /// 任意一次请求成功后调用：记住「最近可用地址」，下次直接复用
    public func markBaseUrlGood(_ urlString: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        prefs.set(trimmed, forKey: Self.prefLastGoodKey)
    }

    /// 信标命中：更新缓存与"最近可用"，供界面立即刷新
    public func applyBeaconUrl(_ urlString: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        cachedBaseUrl = trimmed
        prefs.set(trimmed, forKey: Self.prefLastGoodKey)
    }

    /// 当前地址已失效，强制下次重新选路
    public func invalidateBaseUrl() {
        cachedBaseUrl = nil
    }

    /// 自检并自动切换：依次尝试「当前地址 → 信标地址 → 最近可用 → 回环」，
    /// 全部失败时清掉失效的手填局域网地址，避免陈旧 IP 永久卡死。
    public func checkConnection(completion: @escaping (Bool) -> Void) {
        var candidates: [String] = [resolveBaseUrl()]
        if let beacon = prefs.string(forKey: Self.prefBeaconUrlKey),
           !beacon.trimmingCharacters(in: .whitespaces).isEmpty {
            candidates.append(beacon)
        }
        if let lastGood = prefs.string(forKey: Self.prefLastGoodKey),
           !lastGood.trimmingCharacters(in: .whitespaces).isEmpty {
            candidates.append(lastGood)
        }
        candidates.append("http://127.0.0.1:\(Self.defaultPort)")

        var seen = Set<String>()
        let unique = candidates.filter { !$0.isEmpty && seen.insert($0).inserted }

        tryCandidates(unique, index: 0) { [weak self] ok in
            guard let self = self else { return }
            if !ok {
                let manual = self.prefs.string(forKey: Self.prefManualUrlKey)?
                    .trimmingCharacters(in: .whitespaces) ?? ""
                if let custom = self.prefs.string(forKey: self.prefCustomUrlKey)?
                    .trimmingCharacters(in: .whitespaces),
                   !custom.isEmpty, !Self.isLoopback(custom), custom != manual {
                    // 清掉失效的「自动发现」地址；用户手填的地址保留（尊重明确意图）
                    self.prefs.set("", forKey: self.prefCustomUrlKey)
                }
                self.cachedBaseUrl = nil
            }
            DispatchQueue.main.async { completion(ok) }
        }
    }

    private func tryCandidates(_ list: [String], index: Int, completion: @escaping (Bool) -> Void) {
        guard index < list.count else {
            completion(false)
            return
        }
        let baseUrl = list[index]
        guard let url = URL(string: "\(baseUrl)/api/online/status") else {
            tryCandidates(list, index: index + 1, completion: completion)
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        session.dataTask(with: request) { [weak self] _, response, _ in
            let ok = (response as? HTTPURLResponse)?.statusCode == 200
            if ok {
                self?.cachedBaseUrl = baseUrl
                self?.markBaseUrlGood(baseUrl)
                completion(true)
            } else {
                self?.tryCandidates(list, index: index + 1, completion: completion)
            }
        }.resume()
    }

    public func fetchCategories(completion: @escaping (Result<OnlineCategoriesResult, Error>) -> Void) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/categories") else {
            completion(.failure(NSError(domain: "OnlineGallery", code: -1, userInfo: [NSLocalizedDescriptionKey: "无效的服务器地址"])))
            return
        }
        session.dataTask(with: url) { data, _, error in
            if let error = error {
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }
            guard let data = data else {
                DispatchQueue.main.async { completion(.failure(NSError(domain: "OnlineGallery", code: -2, userInfo: [NSLocalizedDescriptionKey: "返回数据为空"]))) }
                return
            }
            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
                var categories: [OnlineCategoryItem] = []
                if let catArr = json["categories"] as? [[String: Any]] {
                    for item in catArr {
                        if let name = item["name"] as? String, let count = item["count"] as? Int {
                            categories.append(OnlineCategoryItem(name: name, count: count))
                        }
                    }
                }
                var stages: [OnlineCategoryItem] = []
                if let stageArr = json["stages"] as? [[String: Any]] {
                    for item in stageArr {
                        if let name = item["name"] as? String, let count = item["count"] as? Int {
                            stages.append(OnlineCategoryItem(name: name, count: count))
                        }
                    }
                }
                let total = (json["total"] as? Int) ?? 0
                let result = OnlineCategoriesResult(categories: categories, stages: stages, total: total)
                DispatchQueue.main.async { completion(.success(result)) }
            } catch {
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }.resume()
    }

    public func fetchWorks(category: String? = nil, query: String? = nil, completion: @escaping (Result<[OnlineWorkEntry], Error>) -> Void) {
        let baseUrl = resolveBaseUrl()
        var components = URLComponents(string: "\(baseUrl)/api/online/works")
        var queryItems: [URLQueryItem] = []
        if let cat = category, !cat.isEmpty {
            queryItems.append(URLQueryItem(name: "category", value: cat))
        }
        if let q = query, !q.isEmpty {
            queryItems.append(URLQueryItem(name: "query", value: q))
        }
        if !queryItems.isEmpty {
            components?.queryItems = queryItems
        }
        guard let url = components?.url else {
            completion(.failure(NSError(domain: "OnlineGallery", code: -1, userInfo: [NSLocalizedDescriptionKey: "URL 构造失败"])))
            return
        }

        session.dataTask(with: url) { data, _, error in
            if let error = error {
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }
            guard let data = data else {
                DispatchQueue.main.async { completion(.success([])) }
                return
            }
            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
                var entries: [OnlineWorkEntry] = []
                if let arr = json["works"] as? [[String: Any]] {
                    for w in arr {
                        if let entry = OnlineWorkEntry.from(dict: w) {
                            entries.append(entry)
                        }
                    }
                }
                DispatchQueue.main.async { completion(.success(entries)) }
            } catch {
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }.resume()
    }

    public func loadImage(path: String, isThumbnail: Bool = true, maxPixel: CGFloat = 200, completion: @escaping (UIImage?) -> Void) {
        let cacheKey = "\(path)_\(isThumbnail ? "thumb" : "full")" as NSString
        if let memoryCached = imageCache.object(forKey: cacheKey) {
            completion(memoryCached)
            return
        }

        // 尝试磁盘缓存
        let safeFileName = cacheKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_")
        let diskURL = diskCacheURL.appendingPathComponent("\(safeFileName).jpg")
        if let diskData = try? Data(contentsOf: diskURL), let diskImage = UIImage(data: diskData) {
            imageCache.setObject(diskImage, forKey: cacheKey)
            completion(diskImage)
            return
        }

        let baseUrl = resolveBaseUrl()
        var components = URLComponents(string: "\(baseUrl)/api/online/image")
        components?.queryItems = [
            URLQueryItem(name: "path", value: path),
            URLQueryItem(name: "thumb", value: isThumbnail ? "1" : "0")
        ]
        guard let url = components?.url else {
            completion(nil)
            return
        }

        session.dataTask(with: url) { [weak self] data, _, _ in
            guard let self = self, let data = data, let image = UIImage(data: data) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            // 写入内存与磁盘缓存
            self.imageCache.setObject(image, forKey: cacheKey)
            try? data.write(to: diskURL)
            DispatchQueue.main.async { completion(image) }
        }.resume()
    }

    public func recordUse(workId: String, platform: String? = nil, completion: ((OnlineUseResult) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/use-work") else {
            completion?(OnlineUseResult(ok: false, workId: workId, useCount: 0, remainingUses: 0, moved: false, message: "URL 错误"))
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var payload: [String: Any] = ["workId": workId]
        if let p = platform { payload["platform"] = p }
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        session.dataTask(with: request) { data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async {
                    completion?(OnlineUseResult(ok: false, workId: workId, useCount: 0, remainingUses: 0, moved: false, message: "请求失败"))
                }
                return
            }
            let ok = (json["ok"] as? Bool) ?? false
            let useCount = (json["useCount"] as? Int) ?? 1
            let remaining = (json["remainingUses"] as? Int) ?? 0
            let moved = (json["moved"] as? Bool) ?? false
            let msg = (json["message"] as? String) ?? ""
            let res = OnlineUseResult(ok: ok, workId: workId, useCount: useCount, remainingUses: remaining, moved: moved, message: msg)
            DispatchQueue.main.async { completion?(res) }
        }.resume()
    }

    public func deleteWork(workId: String, remark: String? = nil, completion: ((Bool, String) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/delete-work") else {
            completion?(false, "URL 错误")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var payload: [String: Any] = ["workId": workId, "deviceName": UIDevice.current.model]
        if let remark = remark, !remark.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            payload["remark"] = remark.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        session.dataTask(with: request) { data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async { completion?(false, "请求失败") }
                return
            }
            let ok = (json["ok"] as? Bool) ?? false
            let msg = (json["message"] as? String) ?? ""
            DispatchQueue.main.async { completion?(ok, msg) }
        }.resume()
    }

    public func resetWork(workId: String, completion: ((Bool, String) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/reset-work") else {
            completion?(false, "URL 错误")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let payload: [String: Any] = ["workId": workId]
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        session.dataTask(with: request) { data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async { completion?(false, "请求失败") }
                return
            }
            let ok = (json["ok"] as? Bool) ?? false
            let msg = (json["message"] as? String) ?? ""
            DispatchQueue.main.async { completion?(ok, msg) }
        }.resume()
    }
}
