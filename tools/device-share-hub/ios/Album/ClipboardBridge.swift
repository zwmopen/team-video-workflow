import Foundation
import UIKit

final class ClipboardBridge {
    static let shared = ClipboardBridge()
    private var observing = false
    private var lastText = ""
    private var seen: [String: Date] = [:]

    func start() {
        guard !observing else { return }
        observing = true
        NotificationCenter.default.addObserver(
            self, selector: #selector(changed),
            name: UIPasteboard.changedNotification, object: UIPasteboard.general)
        captureAndSend()
    }

    func stop() {
        guard observing else { return }
        observing = false
        NotificationCenter.default.removeObserver(
            self, name: UIPasteboard.changedNotification, object: UIPasteboard.general)
    }

    func receive(_ data: Data) throws {
        guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = object["items"] as? [[String: Any]] else { return }
        let sender = object["senderId"] as? String ?? ""
        let origin = object["originId"] as? String ?? sender
        let messageId = object["messageId"] as? String
            ?? "\(sender)-\(data.hashValue)"
        let hopLimit = max(0, min(8, object["hopLimit"] as? Int ?? 0))
        pruneSeen()
        guard seen[messageId] == nil else { return }
        seen[messageId] = Date()
        if let newest = items.filter({ ($0["kind"] as? String) == "clipboard"
            && !($0["deleted"] as? Bool ?? false) })
            .max(by: { ($0["updatedAt"] as? Int64 ?? 0)
                < ($1["updatedAt"] as? Int64 ?? 0) }),
           let text = newest["text"] as? String, !text.isEmpty {
            lastText = text
            DispatchQueue.main.async {
                UIPasteboard.general.string = text
            }
        }
        guard hopLimit > 0 else { return }
        object["senderId"] = DeviceIdentity.id
        object["originId"] = origin
        object["messageId"] = messageId
        object["hopLimit"] = hopLimit - 1
        let forwarded = try JSONSerialization.data(withJSONObject: object)
        send(forwarded, excluding: [sender, origin])
    }

    @objc private func changed() {
        captureAndSend()
    }

    private func captureAndSend() {
        guard observing, let text = UIPasteboard.general.string?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty, text != lastText else { return }
        lastText = text
        let messageId = "ios-clip-\(UUID().uuidString.lowercased())"
        let item: [String: Any] = [
            "id": messageId, "kind": "clipboard", "text": text,
            "updatedAt": Int64(Date().timeIntervalSince1970 * 1000),
            "deleted": false
        ]
        let object: [String: Any] = [
            "senderId": DeviceIdentity.id, "originId": DeviceIdentity.id,
            "messageId": messageId, "hopLimit": 4, "items": [item]
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return }
        seen[messageId] = Date()
        send(data, excluding: [])
    }

    private func send(_ data: Data, excluding ids: Set<String>) {
        for peer in PeerDirectory.shared.peers() where !ids.contains(peer.id) {
            guard let url = URL(string: "http://\(peer.host):\(peer.port)/v2/clipboard") else {
                continue
            }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.httpBody = data
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("\(data.count)", forHTTPHeaderField: "Content-Length")
            URLSession.shared.dataTask(with: request).resume()
        }
    }

    private func pruneSeen() {
        let cutoff = Date().addingTimeInterval(-60 * 60)
        seen = seen.filter { $0.value >= cutoff }
    }
}
