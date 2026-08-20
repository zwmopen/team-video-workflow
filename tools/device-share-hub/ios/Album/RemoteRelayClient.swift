import Foundation

/// HTTPS control-plane client for relay login, presence and remote task polling.
final class RemoteRelayClient {
    private static let connectTimeout: TimeInterval = 8
    private static let readTimeout: TimeInterval = 20
    private static let maxResponseBytes = 2 * 1024 * 1024

    private static let urlSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = readTimeout
        configuration.timeoutIntervalForResource = readTimeout
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
                                        body: ["workspaceId": workspaceId, "deviceId": deviceId])
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
                                       token: nil, body: body)
        guard let token = response["token"] as? String, !token.isEmpty else {
            throw RemoteRelayError.invalidResponse
        }
        return Session(endpoint: base, token: token,
                       expiresAt: response["expiresAt"] as? TimeInterval ?? 0,
                       deviceId: deviceId)
    }

    static func heartbeat(_ session: Session) throws {
        _ = try requestJSON(url: "\(session.endpoint)/v1/presence", method: "POST",
                            token: session.token, body: [:])
    }

    static func inbox(_ session: Session) throws -> [[String: Any]] {
        let response = try requestJSON(url: "\(session.endpoint)/v1/inbox", method: "GET",
                                       token: session.token, body: nil)
        return response["transfers"] as? [[String: Any]] ?? []
    }

    static func transfer(_ session: Session, transferId: String) throws -> [String: Any] {
        guard isSafeId(transferId) else { throw RemoteRelayError.invalidTransferId }
        let response = try requestJSON(
            url: "\(session.endpoint)/v1/transfers/\(transferId)", method: "GET",
            token: session.token, body: nil
        )
        guard let transfer = response["transfer"] as? [String: Any] else {
            throw RemoteRelayError.invalidResponse
        }
        return transfer
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
                                   body: [String: Any]?) throws -> [String: Any] {
        guard let requestURL = URL(string: url) else { throw RemoteRelayError.invalidURL }
        var request = URLRequest(url: requestURL,
                                 cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: readTimeout)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
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
    case invalidResponse
    case invalidTransferId
    case responseTooLarge
    case remote(String)

    var errorDescription: String? {
        switch self {
        case .httpsRequired: return "远程服务必须使用 HTTPS。"
        case .invalidURL: return "远程服务地址无效。"
        case .invalidCertificate: return "远程设备凭证不完整。"
        case .invalidResponse: return "远程服务响应格式无效。"
        case .invalidTransferId: return "远程任务标识无效。"
        case .responseTooLarge: return "远程服务响应过大。"
        case .remote(let detail): return detail
        }
    }
}
