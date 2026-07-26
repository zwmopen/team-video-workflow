import Foundation

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
}

final class PeerDirectory {
    static let shared = PeerDirectory()
    private let lock = NSLock()
    private var values: [String: TransferPeer] = [:]

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
        let result = values.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        lock.unlock()
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
