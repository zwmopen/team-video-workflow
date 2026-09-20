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

        // 2) 当前所在网段的 /24 全量
        for localIP in Self.localIPv4Addresses() {
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
        let deadline = Date().addingTimeInterval(12)

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

        _ = group.wait(timeout: .now() + 14)
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

    /// 枚举本机所有非回环 IPv4 地址（Wi-Fi 与蜂窝均在内）
    private static func localIPv4Addresses() -> [String] {
        var addresses: [String] = []
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return addresses }

        var ptr = first
        while true {
            let ifa = ptr.pointee
            if let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let nameInfoResult = getnameinfo(
                    addr, socklen_t(addr.pointee.sa_len),
                    &hostname, socklen_t(hostname.count),
                    nil, 0, NI_NUMERICHOST
                )
                if nameInfoResult == 0 {
                    let ip = String(cString: hostname)
                    if !ip.isEmpty, !ip.hasPrefix("127."), !addresses.contains(ip) {
                        addresses.append(ip)
                    }
                }
            }
            guard let next = ifa.ifa_next else { break }
            ptr = next
        }
        freeifaddrs(ifaddrPtr)
        return addresses
    }
}
