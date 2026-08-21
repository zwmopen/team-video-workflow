import Foundation

/// Validated, metadata-only view of a relay inbox task.
///
/// File names and paths deliberately stay outside this boundary. Encrypted
/// object download/decryption will consume the task in a later phase.
struct RemoteRelayTask {
    static let maxObjects = 1_000

    let transferId: String
    let senderDeviceId: String
    let recipientDeviceId: String
    let status: String
    let objectCount: Int
    let totalCipherBytes: Int64
    let expiresAt: Int64

    static func parse(_ object: [String: Any], expectedRecipientId: String,
                      nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> RemoteRelayTask {
        guard let transferId = safeId(object["transferId"] as? String),
              let senderDeviceId = safeId(object["senderDeviceId"] as? String),
              let recipientDeviceId = safeId(object["recipientDeviceId"] as? String),
              recipientDeviceId == expectedRecipientId,
              let status = object["status"] as? String, status == "ready",
              let expiresAt = int64(object["expiresAt"]), expiresAt > 0,
              let objects = object["objects"] as? [[String: Any]],
              !objects.isEmpty, objects.count <= maxObjects else {
            throw RemoteRelayError.invalidTask
        }

        var indexes = Set<Int>()
        var totalCipherBytes: Int64 = 0
        for item in objects {
            guard let index = int(item["index"]), index >= 0,
                  indexes.insert(index).inserted,
                  let cipherBytes = int64(item["cipherBytes"]), cipherBytes > 0,
                  let hash = item["cipherSha256"] as? String,
                  hash.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil,
                  Int64.max - totalCipherBytes >= cipherBytes else {
                throw RemoteRelayError.invalidTask
            }
            totalCipherBytes += cipherBytes
        }
        if let declared = int64(object["totalCipherBytes"]), declared != totalCipherBytes {
            throw RemoteRelayError.invalidTask
        }
        return RemoteRelayTask(transferId: transferId,
                               senderDeviceId: senderDeviceId,
                               recipientDeviceId: recipientDeviceId,
                               status: status,
                               objectCount: objects.count,
                               totalCipherBytes: totalCipherBytes,
                               expiresAt: expiresAt)
    }

    func expired(at nowMs: Int64) -> Bool { expiresAt <= nowMs }

    private static func safeId(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              value.count >= 8, value.count <= 128,
              value.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else {
            return nil
        }
        return value
    }

    private static func int(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber { return value.intValue }
        return nil
    }

    private static func int64(_ value: Any?) -> Int64? {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? NSNumber { return value.int64Value }
        return nil
    }
}
