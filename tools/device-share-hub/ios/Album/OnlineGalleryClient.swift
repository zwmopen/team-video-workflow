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

    public func resolveBaseUrl() -> String {
        if let cached = cachedBaseUrl, !cached.isEmpty {
            return cached
        }
        if let custom = prefs.string(forKey: prefCustomUrlKey), !custom.trimmingCharacters(in: .whitespaces).isEmpty {
            let trimmed = custom.trimmingCharacters(in: .whitespaces)
            cachedBaseUrl = trimmed
            return trimmed
        }
        // 默认优先直连电脑端局域网 IP
        let defaultUrl = "http://192.168.0.106:\(Self.defaultPort)"
        cachedBaseUrl = defaultUrl
        return defaultUrl
    }

    public func setCustomBaseUrl(_ urlString: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespaces)
        prefs.set(trimmed, forKey: prefCustomUrlKey)
        cachedBaseUrl = trimmed.isEmpty ? nil : trimmed
    }

    public func checkConnection(completion: @escaping (Bool) -> Void) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/status") else {
            completion(false)
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        session.dataTask(with: request) { _, response, _ in
            let ok = (response as? HTTPURLResponse)?.statusCode == 200
            DispatchQueue.main.async { completion(ok) }
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
