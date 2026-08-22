import Foundation

/// Public relay enrollment data. Private keys stay in Keychain and session tokens stay in memory.
struct RemoteRelayProfile {
    struct Stored {
        let endpoint: String
        let certificate: [String: Any]
        let certificateSignature: String
    }

    private static let defaultsKey = "album.remoteRelayProfile.v1"

    static func save(endpoint: String, certificate: [String: Any],
                     certificateSignature: String) throws {
        let normalized = try RemoteRelayClient.normalizeEndpoint(endpoint)
        try validate(certificate: certificate)
        guard !certificateSignature.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw RemoteRelayError.invalidCertificate
        }
        let object: [String: Any] = [
            "endpoint": normalized,
            "certificate": certificate,
            "certificateSignature": certificateSignature.trimmingCharacters(in: .whitespacesAndNewlines)
        ]
        let data = try JSONSerialization.data(withJSONObject: object, options: [])
        UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: defaultsKey)
    }

    static func load() -> Stored? {
        guard let raw = UserDefaults.standard.string(forKey: defaultsKey),
              let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []),
              let profile = object as? [String: Any],
              let endpoint = profile["endpoint"] as? String,
              let certificate = profile["certificate"] as? [String: Any],
              let signature = profile["certificateSignature"] as? String,
              let normalizedEndpoint = try? RemoteRelayClient.normalizeEndpoint(endpoint),
              (try? validate(certificate: certificate)) != nil,
              !signature.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return Stored(endpoint: normalizedEndpoint, certificate: certificate,
                      certificateSignature: signature)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: defaultsKey)
    }

    private static func validate(certificate: [String: Any]) throws {
        guard let workspaceId = certificate["workspaceId"] as? String,
              let deviceId = certificate["deviceId"] as? String,
              !workspaceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !deviceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              certificate["signingPublicKey"] != nil,
              certificate["agreementPublicKey"] != nil else {
            throw RemoteRelayError.invalidCertificate
        }
    }
}
