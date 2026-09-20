import Foundation

/// 局域网自动发现「电脑在线相册服务」（端口 45835）。
///
/// 设计取舍：iPhone 侧刻意使用 **单播（unicast）网段扫描**，而不是接收 UDP 广播。
/// 原因：iOS 14 起接收/发送广播与组播需要 `com.apple.developer.networking.multicast`
/// 权限，而该权限需 Apple 单独审批，AltStore 个人签名侧载拿不到。
/// 单播探测无此限制，因此在所有分发方式下都能稳定工作。
///
/// 命中后写入 `customPcServerUrl`，后续直接复用，不再扫描。
public final class LanDiscovery {
    public static let shared = LanDiscovery()
    public static let galleryPort = 45835
    private static let serviceMarker = "DeviceShareHub-OnlineGallery"

    private let workQueue = DispatchQueue(label: "com.zwm.gallery.landiscovery", qos: .utility)
    private var isRunning = false
    private let stateLock = NSLock()

    private init() {}

    /// 开始扫描。命中回调 URL；未命中回调 nil。可在失败后反复调用（带内部互斥）。
    public func discover(completion: @escaping (String?) -> Void) {
        stateLock.lock()
        if isRunning {
            stateLock.unlock()
            completion(nil)
            return
        }
        isRunning = true
        stateLock.unlock()

        workQueue.async { [weak self] in
            guard let self = self else { return }
            let found = self.scan()
            self.stateLock.lock()
            self.isRunning = false
            self.stateLock.unlock()

            if let url = found {
                OnlineGalleryClient.shared.setCustomBaseUrl(url)
            }
            DispatchQueue.main.async { completion(found) }
        }
    }

    // MARK: - 扫描主体

    private func scan() -> String? {
        var candidates: [String] = []

        // 1) 历史可用地址优先（电脑 IP 通常不变）
        let prefs = UserDefaults.standard
        for key in [OnlineGalleryClient.prefLastGoodKey, OnlineGalleryClient.prefBeaconUrlKey] {
            if let value = prefs.string(forKey: key), let host = Self.host(from: value),
               !host.isEmpty, !candidates.contains(host) {
                candidates.append(host)
            }
        }

        // 2) 真实局域网网段的 /24 全量
        //
        // 【关键修复】原实现把 getifaddrs 返回的**所有**非回环 IPv4 都拿来做 /24 扫描，
        // 其中包含蜂窝接口（pdp_ip0，运营商大内网 10.x/172.x/100.64.x）。
        // 结果是：手机同时开着 Wi-Fi 和蜂窝时，候选地址会翻倍到 500+ 个，
        // 而扫描有 12 秒总预算 —— 一旦先扫到蜂窝那个 /24，12 秒会在
        // 254 个不可达地址上耗尽，**Wi-Fi 网段根本还没轮到**就 break，
        // discover() 返回 nil，表现就是「iPhone 读不到电脑在线相册」。
        // 现在按接口名剔除蜂窝/隧道类接口，只扫真实局域网。
        let lanAddresses = Self.lanIPv4Addresses()
        for localIP in lanAddresses {
            let parts = localIP.split(separator: ".")
            guard parts.count == 4 else { continue }
            let prefix = "\(parts[0]).\(parts[1]).\(parts[2])."
            if prefix.hasPrefix("127.") { continue }
            for i in 1...254 {
                let host = prefix + String(i)
                if !candidates.contains(host) { candidates.append(host) }
            }
        }

        guard !candidates.isEmpty else { return nil }

        let resultLock = NSLock()
        var found: String? = nil
        let group = DispatchGroup()
        let slots = DispatchSemaphore(value: 48)
        // 单个 /24 在 48 并发 + 1.6s 超时下约需 8~9 秒；给到 20 秒覆盖多网段与慢 Wi-Fi
        let deadline = Date().addingTimeInterval(20)

        for host in candidates {
            resultLock.lock()
            let done = found != nil
            resultLock.unlock()
            if done || Date() > deadline { break }

            slots.wait()
            group.enter()
            DispatchQueue.global(qos: .utility).async { [weak self] in
                defer {
                    slots.signal()
                    group.leave()
                }
                guard let self = self else { return }
                resultLock.lock()
                let alreadyFound = found != nil
                resultLock.unlock()
                if alreadyFound { return }

                if self.probe(host: host) {
                    resultLock.lock()
                    if found == nil { found = "http://\(host):\(Self.galleryPort)" }
                    resultLock.unlock()
                }
            }
        }

        _ = group.wait(timeout: .now() + 24)
        resultLock.lock()
        let final = found
        resultLock.unlock()
        return final
    }

    /// 单播探测：命中则返回 true（返回体必须带服务标识，避免误判其它 45835 占用者）
    private func probe(host: String) -> Bool {
        guard let url = URL(string: "http://\(host):\(Self.galleryPort)/api/online/status") else {
            return false
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 1.0
        request.cachePolicy = .reloadIgnoringLocalCacheData

        let sem = DispatchSemaphore(value: 0)
        var hit = false
        let task = URLSession.shared.dataTask(with: request) { data, response, _ in
            defer { sem.signal() }
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data = data,
                  let text = String(data: data, encoding: .utf8),
                  text.contains(Self.serviceMarker) else { return }
            hit = true
        }
        task.resume()
        _ = sem.wait(timeout: .now() + 1.6)
        return hit
    }

    // MARK: - 工具

    private static func host(from url: String) -> String? {
        var s = url.trimmingCharacters(in: .whitespaces)
        if let range = s.range(of: "://") { s = String(s[range.upperBound...]) }
        if let slash = s.firstIndex(of: "/") { s = String(s[..<slash]) }
        if let colon = s.lastIndex(of: ":") { s = String(s[..<colon]) }
        return s.isEmpty ? nil : s
    }

    /// 枚举本机**真实局域网**的 IPv4 地址，按「最可能是家庭/公司 LAN」排序。
    ///
    /// 与 `localIPv4Addresses()` 的区别：只保留 `en*`（Wi-Fi / 以太网）接口，
    /// 剔除蜂窝（`pdp_ip*`）、VPN/隧道（`utun*` / `ipsec*`）、AirDrop P2P（`awdl*` / `llw*`）
    /// 与网桥（`bridge*`）—— 这些网段的 /24 扫描纯属浪费扫描预算，会把 Wi-Fi 网段挤掉。
    private static func lanIPv4Addresses() -> [String] {
        let all = localIPv4Addresses()

        // 优先 192.168.x（家庭/小型办公最常见），其次 10.x，再次 172.16-31.x，最后其它
        func rank(_ ip: String) -> Int {
            if ip.hasPrefix("192.168.") { return 0 }
            if ip.hasPrefix("10.") { return 1 }
            if ip.hasPrefix("172.") {
                let second = Int(ip.split(separator: ".").dropFirst().first.map(String.init) ?? "") ?? 0
                if (16...31).contains(second) { return 2 }
            }
            if ip.hasPrefix("100.64.") || ip.hasPrefix("100.65.") { return 9 } // 运营商 CGNAT，最后扫
            return 5
        }

        return all
            .filter { !$0.ip.hasPrefix("169.254.") }   // 链路本地，无意义
            .sorted { rank($0.ip) < rank($1.ip) }
            .map { $0.ip }
    }

    /// 枚举本机所有非回环 IPv4 地址及其所属接口名（Wi-Fi、蜂窝、隧道均在内）
    private static func localIPv4Addresses() -> [(name: String, ip: String)] {
        var addresses: [(name: String, ip: String)] = []
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return addresses }

        var ptr = first
        while true {
            let ifa = ptr.pointee
            if let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: ifa.ifa_name)
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let nameInfoResult = getnameinfo(
                    addr, socklen_t(addr.pointee.sa_len),
                    &hostname, socklen_t(hostname.count),
                    nil, 0, NI_NUMERICHOST
                )
                if nameInfoResult == 0 {
                    let ip = String(cString: hostname)
                    if !ip.isEmpty, !ip.hasPrefix("127."),
                       !addresses.contains(where: { $0.ip == ip }) {
                        addresses.append((name: name, ip: ip))
                    }
                }
            }
            guard let next = ifa.ifa_next else { break }
            ptr = next
        }
        freeifaddrs(ifaddrPtr)
        // 只保留 Wi-Fi / 以太网接口（iOS 上 Wi-Fi 是 en0），剔除蜂窝与隧道
        return addresses.filter { $0.name.hasPrefix("en") }
    }
}
