import Foundation
import Darwin

struct TransferPeer: Equatable {
    let id: String
    let name: String
    let model: String
    let host: String
    let port: UInt16
    let state: String
    let workCount: Int
    let lastSeen: Date
}

extension Notification.Name {
    static let transferPeersChanged = Notification.Name("album.transferPeersChanged")
    /// DSH-195：传输界面打开时请求立刻拉一次服务端设备目录
    static let forceServerDirectory = Notification.Name("album.forceServerDirectory")
}

final class PeerDirectory {
    static let shared = PeerDirectory()
    private let lock = NSLock()
    private var values: [String: TransferPeer] = [:]
    /// DSH-195：经电脑服务端 /api/online/phones 发现的设备。
    /// 路由器开客户端隔离时 UDP 广播到不了别的设备，但手机↔电脑 HTTP 一直通，
    /// 服务端手里有全量设备清单 ⇒ 用它给「在线设备」列表兜底。
    private var serverValues: [String: TransferPeer] = [:]
    private static let serverPeerFresh: TimeInterval = 150

    func remember(packet: String, host: String) -> (shouldReply: Bool, registeredComputer: Bool) {
        let parts = packet.components(separatedBy: "|")
        guard parts.count >= 8, parts[0] == "ZWMDS2_HERE", parts[1] == "2",
              !parts[2].isEmpty, parts[2] != DeviceIdentity.id else { return (false, false) }
        let peer = TransferPeer(id: parts[2], name: decode(parts[4]), model: decode(parts[5]),
                                host: host, port: UInt16(parts[3]) ?? 45833,
                                state: decode(parts[6]),
                                workCount: parts.count >= 9 ? max(-1, Int(parts[8]) ?? -1) : -1,
                                lastSeen: Date())
        lock.lock()
        let previous = values[peer.id]
        values[peer.id] = peer
        lock.unlock()
        let changed = previous == nil || previous?.host != peer.host || previous?.name != peer.name ||
            previous?.state != peer.state || previous?.workCount != peer.workCount
        var registeredComputer = false
        if peer.id.hasPrefix("windows-") || peer.model == "Windows PC" {
            var registered = Set(UserDefaults.standard.stringArray(forKey: "album.registeredComputers") ?? [])
            if registered.insert(peer.id).inserted {
                UserDefaults.standard.set(Array(registered).sorted(), forKey: "album.registeredComputers")
                registeredComputer = true
            }
        }
        UserDefaults.standard.set("\(peer.name)|\(peer.model)|\(peer.host)|\(Date().timeIntervalSince1970)",
                                  forKey: "album.lastPeer.\(peer.id)")
        if changed { DispatchQueue.main.async { NotificationCenter.default.post(name: .transferPeersChanged, object: nil) } }
        return (previous == nil || Date().timeIntervalSince(previous!.lastSeen) > 7, registeredComputer)
    }

    func peers() -> [TransferPeer] {
        let cutoff = Date().addingTimeInterval(-15)
        lock.lock()
        values = values.filter { $0.value.lastSeen >= cutoff }
        serverValues = serverValues.filter { $0.value.lastSeen >= cutoff }
        // 合并：UDP 直连发现优先，服务端目录兜底（DSH-195）
        var merged = serverValues
        merged.merge(values) { current, _ in current }
        let result = merged.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        lock.unlock()
        return result
    }

    /// 合并电脑服务端设备目录（/api/online/phones 的原始 JSON data）。
    /// 过滤自己（DeviceIdentity.id / 本机 IP）与掉线设备（lastSeenTs 超过新鲜度）。
    func mergeServerPhones(_ data: Data) {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let devices = root["devices"] as? [[String: Any]] else { return }
        let ownId = DeviceIdentity.id
        let ownHosts = Self.localIPv4Addresses()
        let now = Date()
        lock.lock()
        let before = Set(serverValues.keys)
        for device in devices {
            guard let id = device["deviceId"] as? String, !id.isEmpty,
                  let host = device["ip"] as? String, !host.isEmpty,
                  id != ownId, !ownHosts.contains(host) else { continue }
            if let seenTs = device["lastSeenTs"] as? TimeInterval,
               now.timeIntervalSince1970 - seenTs > Self.serverPeerFresh { continue }
            let port = (device["port"] as? Int).flatMap { UInt16(exactly: $0) } ?? 45833
            let name = device["name"] as? String ?? ""
            let model = device["model"] as? String ?? ""
            var workCount = -1
            if let counts = device["workCounts"] as? [String: Any], let total = counts["total"] as? Int {
                workCount = total
            }
            serverValues[id] = TransferPeer(id: id, name: name, model: model, host: host,
                                            port: port, state: "online", workCount: workCount,
                                            lastSeen: now)
        }
        let changed = before != Set(serverValues.keys)
        lock.unlock()
        if changed {
            DispatchQueue.main.async { NotificationCenter.default.post(name: .transferPeersChanged, object: nil) }
        }
    }

    private static func localIPv4Addresses() -> Set<String> {
        var result = Set<String>()
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0, let start = first else { return result }
        var current: UnsafeMutablePointer<ifaddrs>? = start
        while let pointer = current {
            let interface = pointer.pointee
            defer { current = interface.ifa_next }
            guard let addr = interface.ifa_addr,
                  interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var hostBuffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(MemoryLayout<sockaddr>.size),
                           &hostBuffer, socklen_t(hostBuffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.insert(String(cString: hostBuffer))
            }
        }
        freeifaddrs(start)
        return result
    }

    func diagnosticSummary() -> String {
        let current = peers()
        if current.isEmpty { return "无" }
        return current.map { "\($0.name.isEmpty ? $0.model : $0.name)(\($0.host))" }.joined(separator: "、")
    }

    private func decode(_ value: String) -> String {
        var base = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base.count % 4 != 0 { base.append("=") }
        guard let data = Data(base64Encoded: base), let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }
}
