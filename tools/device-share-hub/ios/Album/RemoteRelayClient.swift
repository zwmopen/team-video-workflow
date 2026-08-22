import Foundation

/// HTTPS control-plane client for relay login, presence and remote task polling.
final class RemoteRelayClient {
    private static let connectTimeout: TimeInterval = 8
    private static let readTimeout: TimeInterval = 20
    private static let objectTimeout: TimeInterval = 30 * 60
    private static let maxResponseBytes = 2 * 1024 * 1024

    private static let urlSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = readTimeout
        configuration.timeoutIntervalForResource = objectTimeout
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }()

    private init() {}

    static func createSession(endpoint: String, certificate: [String: Any],
                              certificateSignature: String) throws -> Session {
        let base = try normalizeEndpoint(endpoint)
        guard let workspaceId = certificate["workspaceId"] as? String,
              let deviceId = certificate["deviceId"] as? String else {
            throw RemoteRelayError.invalidCertificate
        }
        let challenge = try requestJSON(url: "\(base)/v1/challenges", method: "POST",
                                        token: nil,
                                        body: ["workspaceId": workspaceId, "deviceId": deviceId],
                                        workspaceId: workspaceId)
        guard let challengeId = challenge["challengeId"] as? String else {
            throw RemoteRelayError.invalidResponse
        }
        let body: [String: Any] = [
            "certificate": certificate,
            "certificateSignature": certificateSignature,
            "challengeId": challengeId,
            "challengeSignature": try RemoteIdentity.sign(challenge)
        ]
        let response = try requestJSON(url: "\(base)/v1/sessions", method: "POST",
                                       token: nil, body: body, workspaceId: workspaceId)
        guard let token = response["token"] as? String, !token.isEmpty else {
            throw RemoteRelayError.invalidResponse
        }
        return Session(endpoint: base, token: token,
                       expiresAt: response["expiresAt"] as? TimeInterval ?? 0,
                       workspaceId: workspaceId, deviceId: deviceId)
    }

    static func heartbeat(_ session: Session) throws {
        _ = try requestJSON(url: "\(session.endpoint)/v1/presence", method: "POST",
                            token: session.token, body: [:], workspaceId: session.workspaceId)
    }

    static func inbox(_ session: Session) throws -> [[String: Any]] {
        let response = try requestJSON(url: "\(session.endpoint)/v1/inbox", method: "GET",
                                       token: session.token, body: nil,
                                       workspaceId: session.workspaceId)
        return response["transfers"] as? [[String: Any]] ?? []
    }

    /// Creates the WebRTC signaling session; file bytes never go through these calls.
    static func createP2PSession(_ session: Session, recipientDeviceId: String,
                                 transferId: String? = nil) throws -> [String: Any] {
        guard isSafeId(recipientDeviceId) else { throw RemoteRelayError.invalidPeerId }
        var body: [String: Any] = [
            "recipientDeviceId": recipientDeviceId,
            "protocol": "webrtc-datachannel-v1"
        ]
        if let transferId = transferId {
            guard isSafeId(transferId) else { throw RemoteRelayError.invalidTransferId }
            body["transferId"] = transferId
        }
        let response = try requestJSON(url: "\(session.endpoint)/v1/p2p/sessions", method: "POST",
                                       token: session.token, body: body,
                                       workspaceId: session.workspaceId)
        guard let p2p = response["p2p"] as? [String: Any] else {
            throw RemoteRelayError.invalidResponse
        }
        return p2p
    }

    static func p2pSession(_ session: Session, sessionId: String) throws -> [String: Any] {
        guard isSafeId(sessionId) else { throw RemoteRelayError.invalidP2PSessionId }
        let response = try requestJSON(
            url: "\(session.endpoint)/v1/p2p/sessions/\(sessionId)", method: "GET",
            token: session.token, body: nil, workspaceId: session.workspaceId
        )
        guard let p2p = response["p2p"] as? [String: Any] else {
            throw RemoteRelayError.invalidResponse
        }
        return p2p
    }

    static func p2pSessions(_ session: Session) throws -> [[String: Any]] {
        let response = try requestJSON(url: "\(session.endpoint)/v1/p2p/sessions", method: "GET",
                                       token: session.token, body: nil,
                                       workspaceId: session.workspaceId)
        return response["sessions"] as? [[String: Any]] ?? []
    }

    static func sendP2PSignal(_ session: Session, sessionId: String, type: String,
                              data: [String: Any]) throws {
        guard isSafeId(sessionId), !type.isEmpty else {
            throw RemoteRelayError.invalidP2PSessionId
        }
        _ = try requestJSON(
            url: "\(session.endpoint)/v1/p2p/sessions/\(sessionId)/signals", method: "POST",
            token: session.token, body: ["type": type, "data": data],
            workspaceId: session.workspaceId
        )
    }

    static func closeP2PSession(_ session: Session, sessionId: String) throws {
        guard isSafeId(sessionId) else { throw RemoteRelayError.invalidP2PSessionId }
        _ = try requestJSON(
            url: "\(session.endpoint)/v1/p2p/sessions/\(sessionId)/close", method: "POST",
            token: session.token, body: [:], workspaceId: session.workspaceId
        )
    }

    static func transfer(_ session: Session, transferId: String) throws -> [String: Any] {
        guard isSafeId(transferId) else { throw RemoteRelayError.invalidTransferId }
        let response = try requestJSON(
            url: "\(session.endpoint)/v1/transfers/\(transferId)", method: "GET",
            token: session.token, body: nil, workspaceId: session.workspaceId
        )
        guard let transfer = response["transfer"] as? [String: Any] else {
            throw RemoteRelayError.invalidResponse
        }
        return transfer
    }

    /// Streams one ordinary public object to disk, verifies it, then atomically moves it.
    static func downloadObject(_ session: Session, transferId: String, index: Int,
                               destination: URL, expectedBytes: Int64,
                               expectedSha256: String) throws {
        guard isSafeId(transferId), index >= 0, expectedBytes > 0,
              expectedSha256.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil else {
            throw RemoteRelayError.invalidTask
        }
        guard let url = URL(string: "\(session.endpoint)/v1/transfers/\(transferId)/objects/\(index)") else {
            throw RemoteRelayError.invalidURL
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: objectTimeout)
        request.httpMethod = "GET"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(session.token)", forHTTPHeaderField: "Authorization")
        request.setValue(session.workspaceId, forHTTPHeaderField: "X-Workspace-Id")
        let parent = destination.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        let temporary = destination.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: temporary)

        let semaphore = DispatchSemaphore(value: 0)
        var downloadedURL: URL?
        var responseStatus = 0
        var responseError: Error?
        let task = urlSession.downloadTask(with: request) { url, response, error in
            downloadedURL = url
            responseStatus = (response as? HTTPURLResponse)?.statusCode ?? 0
            responseError = error
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()
        if let error = responseError { throw error }
        guard (200..<300).contains(responseStatus), let source = downloadedURL else {
            throw RemoteRelayError.remote("远程文件下载失败 \(responseStatus)")
        }
        do {
            let values = try source.resourceValues(forKeys: [.fileSizeKey])
            guard let size = values.fileSize, Int64(size) == expectedBytes else {
                throw RemoteRelayError.remote("远程文件大小校验失败")
            }
            let actual = try SHA256.fileHex(source)
            guard actual.caseInsensitiveCompare(expectedSha256) == .orderedSame else {
                throw RemoteRelayError.remote("远程文件 SHA-256 校验失败")
            }
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: source, to: temporary)
            try FileManager.default.moveItem(at: temporary, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: source)
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
    }

    static func ack(_ session: Session, transferId: String) throws {
        guard isSafeId(transferId) else { throw RemoteRelayError.invalidTransferId }
        _ = try requestJSON(url: "\(session.endpoint)/v1/transfers/\(transferId)/ack",
                            method: "POST", token: session.token, body: [:],
                            workspaceId: session.workspaceId)
    }

    static func normalizeEndpoint(_ endpoint: String) throws -> String {
        var value = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        guard let url = URL(string: value),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/" else {
            throw RemoteRelayError.httpsRequired
        }
        return value
    }

    private static func requestJSON(url: String, method: String, token: String?,
                                   body: [String: Any]?, workspaceId: String? = nil) throws -> [String: Any] {
        guard let requestURL = URL(string: url) else { throw RemoteRelayError.invalidURL }
        var request = URLRequest(url: requestURL,
                                 cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: readTimeout)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let workspaceId = workspaceId, !workspaceId.isEmpty {
            request.setValue(workspaceId, forHTTPHeaderField: "X-Workspace-Id")
        }
        if let body = body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }

        let semaphore = DispatchSemaphore(value: 0)
        var responseData = Data()
        var responseStatus = 0
        var responseError: Error?
        let task = urlSession.dataTask(with: request) { data, response, error in
            responseData = data ?? Data()
            responseStatus = (response as? HTTPURLResponse)?.statusCode ?? 0
            responseError = error
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()
        if let error = responseError { throw error }
        guard responseData.count <= maxResponseBytes else {
            throw RemoteRelayError.responseTooLarge
        }
        if !(200..<300).contains(responseStatus) {
            let detail = String(data: responseData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            throw RemoteRelayError.remote(detail?.isEmpty == false ? detail! : "远程服务返回错误 \(responseStatus)")
        }
        guard !responseData.isEmpty else { return [:] }
        guard let object = try JSONSerialization.jsonObject(with: responseData, options: [])
                as? [String: Any] else {
            throw RemoteRelayError.invalidResponse
        }
        return object
    }

    private static func isSafeId(_ value: String) -> Bool {
        guard value.count >= 8, value.count <= 128 else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_"
        }
    }

    struct Session {
        let endpoint: String
        let token: String
        let expiresAt: TimeInterval
        let workspaceId: String
        let deviceId: String

        var expired: Bool {
            expiresAt > 0 && expiresAt <= Date().timeIntervalSince1970 * 1000
        }
    }
}

enum RemoteRelayError: LocalizedError {
    case httpsRequired
    case invalidURL
    case invalidCertificate
    case invalidPeerId
    case invalidP2PSessionId
    case invalidResponse
    case invalidTransferId
    case invalidTask
    case responseTooLarge
    case remote(String)

    var errorDescription: String? {
        switch self {
        case .httpsRequired: return "远程服务必须使用 HTTPS。"
        case .invalidURL: return "远程服务地址无效。"
        case .invalidCertificate: return "远程设备凭证不完整。"
        case .invalidPeerId: return "直连目标设备无效。"
        case .invalidP2PSessionId: return "直连协商会话无效。"
        case .invalidResponse: return "远程服务响应格式无效。"
        case .invalidTransferId: return "远程任务标识无效。"
        case .invalidTask: return "远程任务内容无效。"
        case .responseTooLarge: return "远程服务响应过大。"
        case .remote(let detail): return detail
        }
    }
}
