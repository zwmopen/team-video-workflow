import Foundation

/// Validated view of a relay inbox task. Plain mode carries public file metadata.
struct RemoteRelayTask {
    static let maxObjects = 1_000

    let transferId: String
    let senderDeviceId: String
    let recipientDeviceId: String
    let status: String
    let mode: String
    let contentKind: String
    let objectCount: Int
    let totalBytes: Int64
    let objects: [ObjectInfo]
    /// Legacy name retained while old encrypted tasks are still accepted.
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
        let mode = object["mode"] as? String ?? "encrypted"
        guard mode == "plain" || mode == "encrypted" else {
            throw RemoteRelayError.invalidTask
        }
        let contentKind = object["contentKind"] as? String ?? "work"
        guard contentKind == "work" else {
            // iOS can receive works, but it does not silently install mobile
            // update packages. Keep the task visible for a compatible Android
            // device instead of treating an APK as a work file.
            throw RemoteRelayError.invalidTask
        }

        var indexes = Set<Int>()
        var parsedObjects: [ObjectInfo] = []
        var totalBytes: Int64 = 0
        for item in objects {
            guard let index = int(item["index"]), index >= 0,
                  indexes.insert(index).inserted,
                  let bytes = int64(mode == "plain" ? (item["bytes"] ?? item["objectBytes"]) : item["cipherBytes"]),
                  bytes > 0,
                  let hash = (mode == "plain" ? (item["sha256"] ?? item["objectSha256"]) : item["cipherSha256"]) as? String,
                  hash.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil,
                  Int64.max - totalBytes >= bytes else {
                throw RemoteRelayError.invalidTask
            }
            let name = item["name"] as? String ?? ""
            guard mode != "plain" || safeName(name) else { throw RemoteRelayError.invalidTask }
            let mime = item["mime"] as? String ?? "application/octet-stream"
            totalBytes += bytes
            parsedObjects.append(ObjectInfo(index: index, bytes: bytes,
                                            sha256: hash.lowercased(), name: name, mime: mime))
        }
        let declaredKey = mode == "plain" ? "totalBytes" : "totalCipherBytes"
        if let declared = int64(object[declaredKey]), declared != totalBytes {
            throw RemoteRelayError.invalidTask
        }
        return RemoteRelayTask(transferId: transferId,
                               senderDeviceId: senderDeviceId,
                               recipientDeviceId: recipientDeviceId,
                               status: status,
                               mode: mode,
                               contentKind: contentKind,
                               objectCount: parsedObjects.count,
                               totalBytes: totalBytes,
                               objects: parsedObjects,
                               totalCipherBytes: totalBytes,
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

    private static func safeName(_ value: String) -> Bool {
        !value.isEmpty && value.count <= 240 && value != "." && value != ".."
            && !value.contains("/") && !value.contains("\\") && !value.contains("\0")
    }

    struct ObjectInfo {
        let index: Int
        let bytes: Int64
        let sha256: String
        let name: String
        let mime: String
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
