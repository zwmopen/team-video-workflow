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
    /// 在线回收站专用：是否已被电脑端标记为垃圾样本
    public let garbage: Bool
    /// 在线回收站专用：人工垃圾备注（quality_tag.json / manifest.json）
    public let garbageRemark: String
    /// 电脑端作品文件夹的绝对路径（用于「复制路径」按钮）
    public let path: String
    /// DSH-135: 首次分享时间戳（毫秒）
    public let firstSharedAtMs: Double
    /// DSH-135: 移入回收站倒计时过期时间戳（毫秒）
    public let expireAtMs: Double
    /// DSH-135: 首发设备名称
    public let originDevice: String
    /// DSH-137: 已分发版本标签列表
    public let dispatchedVersions: [String]
    /// 季节标签：春季 / 夏季 / 秋季 / 冬季 / 四季通用
    public let season: String
    /// 流量标签：精准流量团建 / 泛流量游戏攻略
    public let flowType: String
    /// 完整标签数组
    public let tags: [String]
    /// 对比视图专用：每页成品图对应的原素材请求标识（如 "__src__:P1_封面.png"）
    public let sourceImages: [String]
    /// 对比视图专用：每页原素材原始文件名（如 "cover.jpg"）
    public let sourceNames: [String]
    /// 是否具备原素材对比图
    public let hasSourceCompare: Bool
    /// 原素材与成品最大相似度（0.0 ~ 1.0）
    public let maxSimilarity: Double
    /// 相似度安全审计标签（如 "🛡️相似度安全"）
    public let similarityTag: String

    public init(id: String, title: String, destination: String, stage: String,
                useCount: Int, maxUses: Int, used: Bool, remainingUses: Int,
                statusLabel: String, images: [String], imageCount: Int,
                copyText: String, hasCopyText: Bool, dispatchedTo: [String],
                updatedAt: Double, garbage: Bool = false, garbageRemark: String = "",
                path: String = "", firstSharedAtMs: Double = 0, expireAtMs: Double = 0,
                originDevice: String = "", dispatchedVersions: [String] = [],
                season: String = "四季通用", flowType: String = "精准流量团建", tags: [String] = [],
                sourceImages: [String] = [], sourceNames: [String] = [],
                hasSourceCompare: Bool = false, maxSimilarity: Double = 0, similarityTag: String = "") {
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
        self.garbage = garbage
        self.garbageRemark = garbageRemark
        self.path = path
        self.firstSharedAtMs = firstSharedAtMs
        self.expireAtMs = expireAtMs
        self.originDevice = originDevice
        self.dispatchedVersions = dispatchedVersions
        self.season = season
        self.flowType = flowType
        self.tags = tags
        self.sourceImages = sourceImages
        self.sourceNames = sourceNames
        self.hasSourceCompare = hasSourceCompare || sourceImages.contains(where: { !$0.isEmpty })
        self.maxSimilarity = maxSimilarity
        self.similarityTag = similarityTag
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
        let rawUpdated = (dict["updatedAt"] as? NSNumber)?.doubleValue
            ?? (dict["updatedAt"] as? Double)
            ?? Double((dict["updatedAt"] as? Int64) ?? Int64((dict["updatedAt"] as? Int) ?? 0))
        let updatedAt: Double
        if rawUpdated <= 0 {
            updatedAt = Date().timeIntervalSince1970 * 1000
        } else if rawUpdated < 10000000000.0 {
            updatedAt = rawUpdated * 1000.0
        } else {
            updatedAt = rawUpdated
        }

        // 在线回收站接口在每套作品上挂一个 garbage 对象：
        // ["marked": Bool, "remark": String, "markedBy": String, "markedAt": String]
        var garbage = false
        var garbageRemark = ""
        if let garbageObj = dict["garbage"] as? [String: Any] {
            garbage = (garbageObj["marked"] as? Bool) ?? false
            garbageRemark = ((garbageObj["remark"] as? String) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        let firstSharedAtMs = (dict["firstSharedAtMs"] as? Double) ?? Double((dict["firstSharedAtMs"] as? Int) ?? 0)
        let expireAtMs = (dict["expireAtMs"] as? Double) ?? Double((dict["expireAtMs"] as? Int) ?? 0)
        let originDevice = (dict["originDevice"] as? String) ?? ""
        let dispatchedVersions = (dict["dispatchedVersions"] as? [String]) ?? []
        let tags = (dict["tags"] as? [String]) ?? []
        let sourceImages = (dict["sourceImages"] as? [String]) ?? []
        let sourceNames = (dict["sourceNames"] as? [String]) ?? []
        let hasSourceCompare = (dict["hasSourceCompare"] as? Bool) ?? sourceImages.contains(where: { !$0.isEmpty })
        let maxSimilarity = (dict["maxSimilarity"] as? Double) ?? Double((dict["maxSimilarity"] as? Int) ?? 0)
        let similarityTag = (dict["similarityTag"] as? String) ?? ""

        var season = ((dict["season"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if season.isEmpty {
            let combined = "\(title) \(destination)"
            if ["秋", "中秋", "国庆", "红枫", "银杏", "蟹", "晒秋", "柿子", "桂花"].contains(where: { combined.contains($0) }) {
                season = "秋季"
            } else if ["冬", "滑雪", "温泉", "私汤", "泡汤", "年会", "跨年", "围炉"].contains(where: { combined.contains($0) }) {
                season = "冬季"
            } else if ["夏", "避暑", "玩水", "漂流", "溯溪", "水枪", "桨板", "皮划艇"].contains(where: { combined.contains($0) }) {
                season = "夏季"
            } else if ["春", "踏青", "赏花", "樱花", "采茶", "春游"].contains(where: { combined.contains($0) }) {
                season = "春季"
            } else {
                season = "四季通用"
            }
        }

        var flowType = ((dict["flowType"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if flowType.isEmpty {
            let combined = "\(title) \(destination)"
            if destination.contains("游戏") || ["游戏", "桌游", "破冰", "冷场", "惩罚"].contains(where: { combined.contains($0) }) {
                flowType = "泛流量游戏攻略"
            } else {
                flowType = "精准流量团建"
            }
        }

        return OnlineWorkEntry(
            id: id, title: title, destination: destination, stage: stage,
            useCount: useCount, maxUses: maxUses, used: used, remainingUses: remainingUses,
            statusLabel: statusLabel, images: images, imageCount: imageCount,
            copyText: copyText, hasCopyText: hasCopyText, dispatchedTo: dispatchedTo,
            updatedAt: updatedAt, garbage: garbage, garbageRemark: garbageRemark,
            path: (dict["path"] as? String) ?? "",
            firstSharedAtMs: firstSharedAtMs, expireAtMs: expireAtMs,
            originDevice: originDevice, dispatchedVersions: dispatchedVersions,
            season: season, flowType: flowType, tags: tags,
            sourceImages: sourceImages, sourceNames: sourceNames,
            hasSourceCompare: hasSourceCompare, maxSimilarity: maxSimilarity,
            similarityTag: similarityTag
        )
    }
}

public struct OnlineViewState: Codable, Equatable {
    public let viewMode: String
    public let updatedAt: Double
    public let updatedBy: String
}

public struct OnlineUseResult {
    public let ok: Bool
    public let workId: String
    public let useCount: Int
    public let remainingUses: Int
    public let moved: Bool
    public let message: String
    public let firstSharedAtMs: Double
    public let expireAtMs: Double
    public let originDevice: String

    public init(ok: Bool, workId: String, useCount: Int, remainingUses: Int, moved: Bool, message: String,
                firstSharedAtMs: Double = 0, expireAtMs: Double = 0, originDevice: String = "") {
        self.ok = ok
        self.workId = workId
        self.useCount = useCount
        self.remainingUses = remainingUses
        self.moved = moved
        self.message = message
        self.firstSharedAtMs = firstSharedAtMs
        self.expireAtMs = expireAtMs
        self.originDevice = originDevice
    }
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
    // Cache reads and UIImage decoding must stay off the main thread during list rendering.
    private let imageIOQueue = DispatchQueue(label: "com.zwm.album.onlineImageIO", qos: .userInitiated, attributes: .concurrent)
    private let imageCache = NSCache<NSString, UIImage>()
    private let fileManager = FileManager.default
    private let diskCacheURL: URL

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 25
        config.timeoutIntervalForResource = 60
        config.httpMaximumConnectionsPerHost = 20
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let cacheDirectory = caches.appendingPathComponent("OnlineGalleryImageCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        self.session = URLSession(configuration: config)
        self.diskCacheURL = cacheDirectory
        imageCache.countLimit = 500
        imageCache.totalCostLimit = 120 * 1024 * 1024 // 120MB 内存缓存
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
        // 顺手把本机登记到 PC 端白名单：首次连接 → 直接放行 + 记 last_seen；
        // 后续每次成功 ping 都续期一次。Server 端幂等，无任何阻拦（满足"下载即用"铁律）。
        ensureDeviceRegistered(baseUrl: trimmed)
    }

    /// 向 PC 端登记本机（device_id + device_name）→ 直接进入白名单。
    /// 用 DeviceIdentity.id 作为稳定 device_id（同一次安装永远一致）。
    /// Server 端行为：新设备直接白名单 + 返回 ok；已知设备只更新 last_seen，无任何 UI/弹框。
    public func ensureDeviceRegistered(baseUrl: String) {
        let deviceId = DeviceIdentity.id
        let deviceName = DeviceIdentity.name
        guard let url = URL(string: "\(baseUrl)/api/online/device-register") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 5
        let body: [String: Any] = ["device_id": deviceId, "device_name": deviceName]
        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        } catch {
            return
        }
        URLSession.shared.dataTask(with: request) { _, response, _ in
            // 静默：白名单是辅助能力，断网/PC 未启动/超时都忽略，不影响主流程。
            if let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                NSLog("[DeviceRegister] OK (%@)", deviceId.prefix(8).description)
            }
        }.resume()
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

    /// 跨端视图模式实时联动回调（当电脑端或其他手机切换图标/列表/对比视图时触发）
    public var onRemoteViewStateChanged: ((OnlineViewState) -> Void)?
    private var lastKnownViewStateUpdatedAt: Double = 0

    private func consumeRemoteViewState(_ dict: [String: Any]?) {
        guard let vs = dict,
              let vm = vs["viewMode"] as? String, !vm.isEmpty else { return }
        let updatedAt = (vs["updatedAt"] as? Double) ?? Double((vs["updatedAt"] as? Int) ?? 0)
        let updatedBy = (vs["updatedBy"] as? String) ?? ""
        if updatedAt > lastKnownViewStateUpdatedAt {
            lastKnownViewStateUpdatedAt = updatedAt
            let state = OnlineViewState(viewMode: vm, updatedAt: updatedAt, updatedBy: updatedBy)
            DispatchQueue.main.async { [weak self] in
                self?.onRemoteViewStateChanged?(state)
            }
        }
    }

    public func fetchViewState(completion: ((OnlineViewState?) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/view-state") else {
            completion?(nil)
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        session.dataTask(with: request) { [weak self] data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let vm = json["viewMode"] as? String else {
                DispatchQueue.main.async { completion?(nil) }
                return
            }
            let updatedAt = (json["updatedAt"] as? Double) ?? Double((json["updatedAt"] as? Int) ?? 0)
            let updatedBy = (json["updatedBy"] as? String) ?? ""
            let state = OnlineViewState(viewMode: vm, updatedAt: updatedAt, updatedBy: updatedBy)
            self?.consumeRemoteViewState(json)
            DispatchQueue.main.async { completion?(state) }
        }.resume()
    }

    public func pushViewState(viewMode: String, completion: ((OnlineViewState?) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/view-state") else {
            completion?(nil)
            return
        }
        // 先预推进本地时间戳，防止本机上报的状态在下一拍心跳里回弹
        let nowMs = Date().timeIntervalSince1970 * 1000
        if nowMs > lastKnownViewStateUpdatedAt {
            lastKnownViewStateUpdatedAt = nowMs
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 3
        let payload: [String: Any] = [
            "viewMode": viewMode,
            "device": UIDevice.current.name
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        session.dataTask(with: request) { [weak self] data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let vm = json["viewMode"] as? String else {
                DispatchQueue.main.async { completion?(nil) }
                return
            }
            let updatedAt = (json["updatedAt"] as? Double) ?? Double((json["updatedAt"] as? Int) ?? 0)
            let updatedBy = (json["updatedBy"] as? String) ?? ""
            if let self = self, updatedAt > self.lastKnownViewStateUpdatedAt {
                self.lastKnownViewStateUpdatedAt = updatedAt
            }
            let state = OnlineViewState(viewMode: vm, updatedAt: updatedAt, updatedBy: updatedBy)
            DispatchQueue.main.async { completion?(state) }
        }.resume()
    }

    /// DSH-111：轻量探测「电脑端的作品有没有变」——只请求 `/api/online/status`，
    /// 用 `totalWorks` + `watchdog.lastChangeAt` 拼成一个指纹串。
    ///
    /// 为什么需要它：在线相册如果每隔几十秒直接调 loadOnlineData()，列表会整份重建，
    /// 用户正在滑动或搜索时会被拽回顶部。有了指纹就能先问一句「变了吗」，
    /// 没变就完全不动 UI —— 自动刷新对用户零打扰（与安卓端同口径）。
    ///
    /// 用 lastChangeAt 而不是只看 totalWorks：加了 1 套又删了 1 套时总数不变，
    /// 但 lastChangeAt 会变，能抓到这种「内容变了」的情况。
    ///
    /// - Returns: 形如 "472|1790293067.11" 的指纹；watchdog 缺失时形如 "472|"（调用方会保守刷新）
    public func fetchServerFingerprint(completion: @escaping (Result<String, Error>) -> Void) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/status") else {
            completion(.failure(NSError(domain: "OnlineGallery", code: -1,
                                        userInfo: [NSLocalizedDescriptionKey: "无效的服务器地址"])))
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 2.5
        session.dataTask(with: request) { [weak self] data, _, error in
            if let error = error {
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async {
                    completion(.failure(NSError(domain: "OnlineGallery", code: -2,
                                                userInfo: [NSLocalizedDescriptionKey: "返回数据为空"])))
                }
                return
            }
            if let vs = json["viewState"] as? [String: Any] {
                self?.consumeRemoteViewState(vs)
            }
            let total = json["totalWorks"] as? Int ?? -1
            // watchdog 缺字段时留空，调用方见空串会保守地照常刷新（宁可多刷，不可漏刷）
            var changeAt = ""
            if let wd = json["watchdog"] as? [String: Any],
               let ts = wd["lastChangeAt"] as? Double {
                changeAt = String(ts)
            }
            DispatchQueue.main.async { completion(.success("\(total)|\(changeAt)")) }
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
            // 成功即落盘快照：下次进页面 / 离线时可直渲染（与 Android `OnlineListCache` 同步）
            OnlineListCache.saveCategories(data)
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
                NSLog("[OnlineGalleryClient] fetchCategories failed: %@", error.localizedDescription)
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }.resume()
    }

    public func fetchWorks(category: String? = nil, query: String? = nil,
                           sortKey: String? = nil,
                           completion: @escaping (Result<[OnlineWorkEntry], Error>) -> Void) {
        let baseUrl = resolveBaseUrl()
        var components = URLComponents(string: "\(baseUrl)/api/online/works")
        var queryItems: [URLQueryItem] = []
        if let cat = category, !cat.isEmpty {
            queryItems.append(URLQueryItem(name: "category", value: cat))
        }
        if let q = query, !q.isEmpty {
            queryItems.append(URLQueryItem(name: "query", value: q))
        }
        // DSH-112：排序键上报（与安卓同口径，服务端 SORT_KEYS 早就支持 ?sort=）
        if let sk = sortKey, !sk.isEmpty {
            queryItems.append(URLQueryItem(name: "sort", value: sk))
        }
        if !queryItems.isEmpty {
            components?.queryItems = queryItems
        }
        guard let url = components?.url else {
            completion(.failure(NSError(domain: "OnlineGallery", code: -1, userInfo: [NSLocalizedDescriptionKey: "URL 构造失败"])))
            return
        }

        session.dataTask(with: url) { [weak self] data, _, error in
            if let error = error {
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }
            guard let data = data else {
                DispatchQueue.main.async { completion(.success([])) }
                return
            }
            // 只有「全量列表」才值得做快照：带分类/关键词的响应是子集，
            // 存下去会让下次秒开时看到一个不完整的列表（与 Android 同步）。
            let isFullList = (category ?? "").isEmpty && (query ?? "").isEmpty
            if isFullList {
                OnlineListCache.saveWorks(data)
            }
            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
                if let vs = json["viewState"] as? [String: Any] {
                    self?.consumeRemoteViewState(vs)
                }
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
                NSLog("[OnlineGalleryClient] fetchWorks failed category=%@ query=%@ error=%@",
                      category ?? "<nil>", query ?? "<nil>", error.localizedDescription)
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }.resume()
    }

    /// DSH-102：加 `workId` 参数（默认 nil 保留旧契约）。
    /// 服务端 `image_name_index()` 同名文件只保留首个命中（库内 174 条作品共用 P1_封面.png），
    /// 旧契约 `?path=裸文件名` 会让 iOS 永远拿到第一个扫到的作品的图（与当前浏览无关，
    /// 用户 iPhone 上图错位显示阳澄湖/浙江省攻略就是这条 BUG）。修法：
    /// - 有 workId → `?id=<workId>&file=<basename(path)>` 双键，服务端
    ///   `OnlineGalleryHandler.handle_image()` 会用 workId 定位作品目录 + basename 拼图，
    ///   彻底绕开 image_name_index 同名冲突索引。
    /// - 无 workId → 旧契约 `?path=` 保留（向后兼容，避免破坏 iOS 别的旧调用点）。
    /// DSH-099 失败 NSLog 保留。
    public func loadImage(path: String, workId: String? = nil, isThumbnail: Bool = true, maxPixel: CGFloat = 200, completion: @escaping (UIImage?) -> Void) {
        let prefix = (workId ?? "").isEmpty ? "" : "\(workId!)_"
        let cacheKey = "\(prefix)\(path)_\(isThumbnail ? "thumb" : "full")" as NSString
        if let memoryCached = imageCache.object(forKey: cacheKey) {
            completion(memoryCached)
            return
        }

        let baseUrl = resolveBaseUrl()
        var components = URLComponents(string: "\(baseUrl)/api/online/image")
        // 缓存 key 必须包含 workId，避免不同作品同名图共享同一磁盘缓存条目
        var queryItems: [URLQueryItem] = [URLQueryItem(name: "thumb", value: isThumbnail ? "1" : "0")]
        if let workId = workId, !workId.isEmpty {
            // DSH-102 新契约：id+file 双键，支持 __src__: 原素材对比标识
            let bn = path.hasPrefix("__src__:") ? path : (path as NSString).lastPathComponent
            queryItems.append(URLQueryItem(name: "id", value: workId))
            queryItems.append(URLQueryItem(name: "file", value: bn))
        } else {
            // 旧契约：?path=裸文件名（无 workId 的 caller 不传，保持向后兼容）
            queryItems.append(URLQueryItem(name: "path", value: path))
        }
        components?.queryItems = queryItems
        guard let url = components?.url else {
            completion(nil)
            return
        }

        let safeFileName = cacheKey
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        let diskURL = diskCacheURL.appendingPathComponent("\(safeFileName).jpg")
        // 列表渲染时可能连续请求几十张缓存图；磁盘读取和 UIImage 解码放后台，避免阻塞主线程。
        imageIOQueue.async { [weak self] in
            guard let self = self else { return }
            if let diskData = try? Data(contentsOf: diskURL), let diskImage = UIImage(data: diskData) {
                self.imageCache.setObject(diskImage, forKey: cacheKey)
                DispatchQueue.main.async { completion(diskImage) }
                return
            }

            self.session.dataTask(with: url) { [weak self] data, _, _ in
                guard let self = self, let data = data, let image = UIImage(data: data) else {
                    // DSH-099 iOS 等价：loadImage 失败要 NSLog（debug 回传铁律）
                    NSLog("[OnlineGalleryClient] loadImage failed: path=%@ isThumbnail=%d",
                          path, isThumbnail ? 1 : 0)
                    DispatchQueue.main.async { completion(nil) }
                    return
                }
                self.imageCache.setObject(image, forKey: cacheKey)
                do {
                    try data.write(to: diskURL)
                } catch {
                    NSLog("[OnlineGalleryClient] loadImage disk write failed: path=%@ error=%@",
                          path, error.localizedDescription)
                }
                DispatchQueue.main.async { completion(image) }
            }.resume()
        }
    }

    /// 同步快速获取缓存图片（若内存或磁盘命中直接返回，0毫秒无缝秒开）
    public func getFastCachedImage(path: String, workId: String? = nil, isThumbnail: Bool = true) -> UIImage? {
        let prefix = (workId ?? "").isEmpty ? "" : "\(workId!)_"
        let cacheKey = "\(prefix)\(path)_\(isThumbnail ? "thumb" : "full")" as NSString
        if let mem = imageCache.object(forKey: cacheKey) {
            return mem
        }
        let safeFileName = cacheKey
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        let diskURL = diskCacheURL.appendingPathComponent("\(safeFileName).jpg")
        if let diskData = try? Data(contentsOf: diskURL), let diskImage = UIImage(data: diskData) {
            imageCache.setObject(diskImage, forKey: cacheKey)
            return diskImage
        }
        return nil
    }

    /// 检查指定路径的高清原画是否已在内存或本地磁盘就绪
    public func hasFullImageCached(path: String, workId: String? = nil) -> Bool {
        let prefix = (workId ?? "").isEmpty ? "" : "\(workId!)_"
        let cacheKey = "\(prefix)\(path)_full" as NSString
        if imageCache.object(forKey: cacheKey) != nil {
            return true
        }
        let safeFileName = cacheKey
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        let diskURL = diskCacheURL.appendingPathComponent("\(safeFileName).jpg")
        return fileManager.fileExists(atPath: diskURL.path)
    }

    /// 检查某作品的所有原画图片是否均已在本地就绪
    public func areAllWorkImagesCachedLocally(_ entry: OnlineWorkEntry) -> Bool {
        guard !entry.images.isEmpty else { return true }
        for img in entry.images {
            if !hasFullImageCached(path: img, workId: entry.id) {
                return false
            }
        }
        return true
    }

    public func recordUse(workId: String, platform: String? = nil, retentionDurationMs: Double = 3600000, versionTag: String? = nil, completion: ((OnlineUseResult) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/use-work") else {
            completion?(OnlineUseResult(ok: false, workId: workId, useCount: 0, remainingUses: 0, moved: false, message: "URL 错误"))
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var payload: [String: Any] = [
            "workId": workId,
            "device": UIDevice.current.name,
            "retentionDurationMs": retentionDurationMs
        ]
        if let p = platform { payload["platform"] = p }
        if let vt = versionTag, !vt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            payload["versionTag"] = vt.trimmingCharacters(in: .whitespacesAndNewlines)
        }
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
            let firstShared = (json["firstSharedAtMs"] as? Double) ?? 0
            let expire = (json["expireAtMs"] as? Double) ?? 0
            let dev = (json["originDevice"] as? String) ?? ""
            let res = OnlineUseResult(ok: ok, workId: workId, useCount: useCount, remainingUses: remaining, moved: moved, message: msg,
                                      firstSharedAtMs: firstShared, expireAtMs: expire, originDevice: dev)
            DispatchQueue.main.async { completion?(res) }
        }.resume()
    }

    /// DSH-138: 手机端在线修改文案同步写回电脑真源文案.txt
    public func updateCopy(workId: String, updatedCopy: String, device: String? = nil, versionTag: String? = nil, completion: ((Bool, String) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/update-copy") else {
            completion?(false, "URL 无效")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var payload: [String: Any] = [
            "workId": workId,
            "updatedCopy": updatedCopy,
            "device": device ?? UIDevice.current.name
        ]
        if let vt = versionTag, !vt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            payload["versionTag"] = vt.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        session.dataTask(with: request) { data, _, err in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async { completion?(false, err?.localizedDescription ?? "网络请求失败") }
                return
            }
            let ok = (json["ok"] as? Bool) ?? false
            let msg = (json["message"] as? String) ?? (ok ? "文案已成功同步至电脑" : "保存失败")
            DispatchQueue.main.async { completion?(ok, msg) }
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

    /// DSH-141: 删除单张图片（安全移入 _垃圾作品样本/_deleted_images/ 零丢失备份）
    public func deleteOnlineImage(workId: String, image: String, completion: ((Bool, String, [String]) -> Void)? = nil) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)/api/online/delete-image") else {
            completion?(false, "URL 错误", [])
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let payload: [String: Any] = [
            "workId": workId,
            "image": image,
            "deviceName": UIDevice.current.name.isEmpty ? UIDevice.current.model : UIDevice.current.name
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        session.dataTask(with: request) { data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async { completion?(false, "请求失败", []) }
                return
            }
            let ok = (json["ok"] as? Bool) ?? false
            let msg = (json["message"] as? String) ?? (json["error"] as? String) ?? ""
            let remaining = (json["remainingImages"] as? [String]) ?? []
            DispatchQueue.main.async { completion?(ok, msg, remaining) }
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

    // ==================================================================
    // 在线回收站（与安卓严格对齐）：已使用 / 已标记垃圾 双 Tab
    //   已使用    -> _已发送1次（微信公众号可发）
    //   已标记垃圾 -> _垃圾作品（后续参考分析），永久保留
    // ==================================================================

    public struct OnlineRecycleResult {
        public let ok: Bool
        public let tab: String
        public let label: String
        public let total: Int
        public let sentCount: Int
        public let garbageCount: Int
        public let works: [OnlineWorkEntry]
    }

    /// 拉取在线回收站某个 Tab 的列表；counts 里同时带两个 Tab 的角标数字。
    public func fetchRecycle(tab: String, completion: @escaping (Result<OnlineRecycleResult, Error>) -> Void) {
        let baseUrl = resolveBaseUrl()
        let wantTab = tab.isEmpty ? "sent" : tab
        var components = URLComponents(string: "\(baseUrl)/api/online/recycle")
        components?.queryItems = [
            URLQueryItem(name: "tab", value: wantTab),
            URLQueryItem(name: "refresh", value: "1")
        ]
        guard let url = components?.url else {
            completion(.failure(NSError(domain: "OnlineGallery", code: -1,
                                        userInfo: [NSLocalizedDescriptionKey: "URL 构造失败"])))
            return
        }
        session.dataTask(with: url) { data, _, error in
            if let error = error {
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async {
                    completion(.failure(NSError(domain: "OnlineGallery", code: -2,
                                                userInfo: [NSLocalizedDescriptionKey: "响应解析失败"])))
                }
                return
            }
            var works: [OnlineWorkEntry] = []
            if let arr = json["works"] as? [[String: Any]] {
                for dict in arr {
                    if let entry = OnlineWorkEntry.from(dict: dict) { works.append(entry) }
                }
            }
            let counts = json["counts"] as? [String: Any]
            let result = OnlineRecycleResult(
                ok: (json["ok"] as? Bool) ?? false,
                tab: (json["tab"] as? String) ?? wantTab,
                label: (json["label"] as? String) ?? "",
                total: (json["total"] as? Int) ?? works.count,
                sentCount: (counts?["sent"] as? Int) ?? 0,
                garbageCount: (counts?["garbage"] as? Int) ?? 0,
                works: works
            )
            self.markBaseUrlGood(baseUrl)
            DispatchQueue.main.async { completion(.success(result)) }
        }.resume()
    }

    /// 回收站「恢复」：移回「已发送0次」+ 次数归零 + 撤销垃圾标记。
    public func restoreWork(workId: String, completion: ((Bool, String) -> Void)? = nil) {
        postWorkAction(path: "/api/online/restore",
                       payload: ["workId": workId, "device": UIDevice.current.model],
                       completion: completion)
    }

    /// 垃圾样本库备注：写入 quality_tag.json + manifest.json。
    public func remarkGarbage(workId: String, remark: String, completion: ((Bool, String) -> Void)? = nil) {
        postWorkAction(path: "/api/online/remark-garbage",
                       payload: ["workId": workId,
                                 "remark": remark,
                                 "device": UIDevice.current.model],
                       completion: completion)
    }

    private func postWorkAction(path: String,
                                payload: [String: Any],
                                completion: ((Bool, String) -> Void)?) {
        let baseUrl = resolveBaseUrl()
        guard let url = URL(string: "\(baseUrl)\(path)") else {
            completion?(false, "URL 错误")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
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
