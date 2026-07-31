import Foundation

struct RelayTaskInfo: Codable {
    static let lifetime: TimeInterval = 60 * 60

    let messageId: String
    let originId: String
    let destinationId: String
    let previousHopId: String
    let contentKind: String
    let expiresAt: TimeInterval
    let hopLimit: Int

    init(object: [String: Any]) {
        let now = Date().timeIntervalSince1970
        messageId = (object["messageId"] as? String).flatMap(Self.safe)
            ?? "relay-\(UUID().uuidString.lowercased())"
        originId = (object["originId"] as? String).flatMap(Self.safe) ?? ""
        destinationId = (object["destinationId"] as? String).flatMap(Self.safe) ?? ""
        previousHopId = (object["previousHopId"] as? String).flatMap(Self.safe)
            ?? ((object["senderId"] as? String).flatMap(Self.safe) ?? "")
        contentKind = object["contentKind"] as? String == "screenshot" ? "screenshot" : "file"
        let suppliedMs = object["expiresAt"] as? TimeInterval ?? 0
        let supplied = suppliedMs / 1000
        expiresAt = min(suppliedMs <= 0 ? now + Self.lifetime : supplied,
                        now + Self.lifetime)
        hopLimit = max(0, min(8, object["hopLimit"] as? Int ?? 4))
    }

    var isRelay: Bool { !destinationId.isEmpty }
    var expired: Bool { Date().timeIntervalSince1970 >= expiresAt || hopLimit <= 0 }

    func forwarded() -> RelayTaskInfo {
        RelayTaskInfo(messageId: messageId, originId: originId,
                      destinationId: destinationId, previousHopId: DeviceIdentity.id,
                      contentKind: contentKind, expiresAt: expiresAt,
                      hopLimit: hopLimit - 1)
    }

    func add(to object: inout [String: Any]) {
        object["messageId"] = messageId
        object["originId"] = originId
        object["destinationId"] = destinationId
        object["previousHopId"] = previousHopId
        object["senderId"] = previousHopId
        object["contentKind"] = contentKind
        object["expiresAt"] = Int64(expiresAt * 1000)
        object["hopLimit"] = hopLimit
    }

    private init(messageId: String, originId: String, destinationId: String,
                 previousHopId: String, contentKind: String, expiresAt: TimeInterval,
                 hopLimit: Int) {
        self.messageId = messageId
        self.originId = originId
        self.destinationId = destinationId
        self.previousHopId = previousHopId
        self.contentKind = contentKind
        self.expiresAt = expiresAt
        self.hopLimit = hopLimit
    }

    private static func safe(_ value: String) -> String? {
        guard value.count >= 6, value.count <= 160,
              value.unicodeScalars.allSatisfy({
                  CharacterSet.alphanumerics.contains($0)
                    || "-_.".unicodeScalars.contains($0)
              }) else { return nil }
        return value
    }
}
