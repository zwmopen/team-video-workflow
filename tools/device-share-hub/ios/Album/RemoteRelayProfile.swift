import Foundation

/// Selectable cross-network transport strategy (mirrors PC & Android).
enum RemoteTransportStrategy: String, CaseIterable {
    case auto = "auto"
    case usbFirst = "usb_first"
    case lanFirst = "lan_first"
    case p2pFirst = "p2p_first"
    case r2First = "r2_first"

    var title: String {
        switch self {
        case .auto: return "自动选择（推荐）"
        case .usbFirst: return "USB 优先"
        case .lanFirst: return "局域网优先"
        case .p2pFirst: return "P2P 优先（失败切 R2）"
        case .r2First: return "R2 云端优先"
        }
    }

    var detail: String {
        switch self {
        case .auto: return "按连接情况自动切换 USB / 局域网 / 云端中转"
        case .usbFirst: return "优先走有线通道，失败自动切换局域网或云端"
        case .lanFirst: return "优先走同一 Wi-Fi 局域网直传"
        case .p2pFirst: return "跨网先试 P2P 直连，不通自动切 Cloudflare R2"
        case .r2First: return "适合 P2P 不稳定环境，直接走 R2 高速原画中转"
        }
    }
}

/// Public relay enrollment data. Private keys stay in Keychain and session tokens stay in memory.
struct RemoteRelayProfile {
    struct Stored {
        let endpoint: String
        let certificate: [String: Any]
        let certificateSignature: String
    }

    private static let defaultsKey = "album.remoteRelayProfile.v1"
    private static let strategyKey = "album.transportStrategy.v1"
    private static var lastEnrollAttemptAt: Date?
    private static let enrollLock = NSLock()

    static var transportStrategy: RemoteTransportStrategy {
        get {
            let raw = UserDefaults.standard.string(forKey: strategyKey) ?? ""
            return RemoteTransportStrategy(rawValue: raw) ?? .auto
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: strategyKey)
        }
    }

    static func enrollViaLanBestEffort(pcBaseURL: String) {
        guard pcBaseURL.hasPrefix("http"),
              let url = URL(string: "\(pcBaseURL)/api/online/remote-enroll") else { return }
        enrollLock.lock()
        if let prev = lastEnrollAttemptAt, load() != nil, Date().timeIntervalSince(prev) < 600 {
            enrollLock.unlock()
            return
        }
        lastEnrollAttemptAt = Date()
        enrollLock.unlock()

        DispatchQueue.global(qos: .utility).async {
            guard let keys = try? RemoteIdentity.publicKeys(),
                  let signingKey = keys["signingPublicKey"],
                  let agreementKey = keys["agreementPublicKey"] else { return }
            let body: [String: Any] = [
                "deviceId": DeviceIdentity.id,
                "deviceName": DeviceIdentity.name,
                "signingPublicKey": signingKey,
                "agreementPublicKey": agreementKey
            ]
            guard let bodyData = try? JSONSerialization.data(withJSONObject: body, options: []) else { return }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.timeoutInterval = 10
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = bodyData
            URLSession.shared.dataTask(with: req) { data, response, _ in
                guard let http = response as? HTTPURLResponse,
                      (200..<300).contains(http.statusCode),
                      let data = data,
                      let obj = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
                      (obj["ok"] as? Bool) == true,
                      let endpoint = obj["endpoint"] as? String,
                      let cert = obj["certificate"] as? [String: Any],
                      let sig = obj["certificateSignature"] as? String else { return }
                try? save(endpoint: endpoint, certificate: cert, certificateSignature: sig)
                RemoteRelayClient.invalidateCachedSession()
            }.resume()
        }
    }

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
