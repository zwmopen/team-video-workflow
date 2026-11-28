import Foundation

/// HTTPS control-plane client for relay login, presence and remote task polling.
final class RemoteRelayClient {
    private final class NoRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }
    private static let connectTimeout: TimeInterval = 8
    private static let readTimeout: TimeInterval = 20
    private static let objectTimeout: TimeInterval = 30 * 60
    private static let maxResponseBytes = 2 * 1024 * 1024

    private static let urlSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = readTimeout
        configuration.timeoutIntervalForResource = objectTimeout
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
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

    static func heartbeat(_ session: Session, inventory: [String: Any] = [:]) throws {
        _ = try requestJSON(url: "\(session.endpoint)/v1/presence", method: "POST",
                            token: session.token, body: inventory, workspaceId: session.workspaceId)
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

    private static var cachedSession: Session?
    private static var unavailableEndpoint: String?
    private static var unavailableUntil: TimeInterval = 0
    private static let sessionLock = NSLock()

    static func ensureSession() throws -> Session {
        guard let profile = RemoteRelayProfile.load() else {
            throw RemoteRelayError.invalidCertificate
        }
        sessionLock.lock()
        defer { sessionLock.unlock() }
        if let existing = cachedSession,
           !existing.expired,
           existing.workspaceId == profile.certificate["workspaceId"] as? String,
           existing.deviceId == profile.certificate["deviceId"] as? String,
           existing.endpoint == profile.endpoint || existing.endpoint == profile.backupEndpoint {
            return existing
        }
        var endpoints = [profile.endpoint]
        if let backup = profile.backupEndpoint {
            endpoints.append(backup)
            if unavailableEndpoint == profile.endpoint && ProcessInfo.processInfo.systemUptime < unavailableUntil {
                endpoints.reverse()
            }
        }
        for (index, endpoint) in endpoints.enumerated() {
            do {
                let session = try createSession(endpoint: endpoint, certificate: profile.certificate,
                                                certificateSignature: profile.certificateSignature)
                cachedSession = session
                return session
            } catch {
                guard index + 1 < endpoints.count, isEndpointUnavailable(error) else { throw error }
            }
        }
        throw RemoteRelayError.invalidURL
    }

    private static func isEndpointUnavailable(_ error: Error) -> Bool {
        if let relayError = error as? RemoteRelayError, case .endpointUnavailable = relayError { return true }
        return error is URLError
    }

    private static func invalidateFailedEndpoint(token: String?) {
        guard let token = token else { return }
        sessionLock.lock()
        defer { sessionLock.unlock() }
        guard let current = cachedSession, current.token == token else { return }
        unavailableEndpoint = current.endpoint
        unavailableUntil = ProcessInfo.processInfo.systemUptime + 45
        cachedSession = nil
    }

    static func invalidateCachedSession() {
        sessionLock.lock()
        cachedSession = nil
        sessionLock.unlock()
    }

    /// Streams one ordinary public object to disk with HTTP Range 206 resume & SHA-256 verification.
    static func downloadObject(_ session: Session, transferId: String, index: Int,
                               destination: URL, expectedBytes: Int64,
                               expectedSha256: String) throws {
        guard isSafeId(transferId), index >= 0, expectedBytes > 0,
              expectedSha256.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil else {
            throw RemoteRelayError.invalidTask
        }
        try downloadWithRangeAndVerify(
            urlString: "\(session.endpoint)/v1/transfers/\(transferId)/objects/\(index)",
            session: session,
            destination: destination,
            expectedBytes: expectedBytes,
            expectedSha256: expectedSha256
        )
    }

    static func downloadWithRangeAndVerify(urlString: String, session: Session,
                                           destination: URL, expectedBytes: Int64,
                                           expectedSha256: String?) throws {
        guard let url = URL(string: urlString) else {
            throw RemoteRelayError.invalidURL
        }
        let parent = destination.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        let temporary = destination.appendingPathExtension("part")

        for attempt in 1...3 {
            var existingLen: Int64 = 0
            if let attrs = try? FileManager.default.attributesOfItem(atPath: temporary.path),
               let sz = attrs[.size] as? NSNumber {
                existingLen = sz.int64Value
            }
            if expectedBytes > 0 && existingLen > expectedBytes {
                try? FileManager.default.removeItem(at: temporary)
                existingLen = 0
            }

            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData,
                                     timeoutInterval: objectTimeout)
            request.httpMethod = "GET"
            request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
            request.setValue("Bearer \(session.token)", forHTTPHeaderField: "Authorization")
            request.setValue(session.workspaceId, forHTTPHeaderField: "X-Workspace-Id")
            if existingLen > 0 {
                request.setValue("bytes=\(existingLen)-", forHTTPHeaderField: "Range")
            }

            let semaphore = DispatchSemaphore(value: 0)
            var downloadedURL: URL?
            var responseStatus = 0
            var responseHeaderSha: String?
            var responseError: Error?
            let task = urlSession.downloadTask(with: request) { fileURL, response, error in
                downloadedURL = fileURL
                if let http = response as? HTTPURLResponse {
                    responseStatus = http.statusCode
                    responseHeaderSha = http.allHeaderFields["X-Object-Sha256"] as? String
                        ?? http.allHeaderFields["x-object-sha256"] as? String
                }
                responseError = error
                semaphore.signal()
            }
            task.resume()
            if semaphore.wait(timeout: .now() + objectTimeout + 15) == .timedOut {
                task.cancel()
                if attempt < 3 { continue }
                throw RemoteRelayError.remote("远程对象下载超时")
            }
            if responseStatus == 416 {
                try? FileManager.default.removeItem(at: temporary)
                continue
            }
            if let error = responseError {
                if attempt < 3 {
                    Thread.sleep(forTimeInterval: 0.6 * Double(attempt))
                    continue
                }
                throw error
            }
            guard (200..<300).contains(responseStatus), let source = downloadedURL else {
                if attempt < 3 && (responseStatus >= 500 || responseStatus == 0) {
                    Thread.sleep(forTimeInterval: 0.6 * Double(attempt))
                    continue
                }
                throw RemoteRelayError.remote("远程文件下载失败 \(responseStatus)")
            }

            do {
                if responseStatus == 206 && existingLen > 0 {
                    let handle = try FileHandle(forUpdating: temporary)
                    handle.seekToEndOfFile()
                    let chunk = try Data(contentsOf: source)
                    handle.write(chunk)
                    handle.closeFile()
                    try? FileManager.default.removeItem(at: source)
                } else {
                    try? FileManager.default.removeItem(at: temporary)
                    try FileManager.default.moveItem(at: source, to: temporary)
                }
                let values = try temporary.resourceValues(forKeys: [.fileSizeKey])
                if expectedBytes > 0 {
                    guard let size = values.fileSize, Int64(size) == expectedBytes else {
                        if attempt < 3 { continue }
                        throw RemoteRelayError.remote("远程文件大小校验失败")
                    }
                }
                let wantSha = (expectedSha256?.isEmpty == false ? expectedSha256 : responseHeaderSha) ?? ""
                if !wantSha.isEmpty {
                    let actual = try SHA256.fileHex(temporary)
                    guard actual.caseInsensitiveCompare(wantSha) == .orderedSame else {
                        try? FileManager.default.removeItem(at: temporary)
                        throw RemoteRelayError.remote("远程文件 SHA-256 校验失败")
                    }
                }
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: temporary, to: destination)
                return
            } catch {
                try? FileManager.default.removeItem(at: source)
                if attempt >= 3 {
                    try? FileManager.default.removeItem(at: temporary)
                    throw error
                }
            }
        }
    }

    static func fetchStats(_ session: Session) throws -> [String: Any] {
        return try requestJSON(url: "\(session.endpoint)/v1/stats", method: "GET",
                               token: session.token, body: nil, workspaceId: session.workspaceId)
    }

    static func fetchCatalogManifest(_ session: Session) throws -> [String: Any] {
        return try requestJSON(url: "\(session.endpoint)/v1/catalog", method: "GET",
                               token: session.token, body: nil, workspaceId: session.workspaceId)
    }

    static func downloadCatalogThumb(_ session: Session, thumbKey: String) throws -> Data {
        guard let url = URL(string: "\(session.endpoint)/v1/catalog/thumbs/\(thumbKey)") else {
            throw RemoteRelayError.invalidURL
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: readTimeout)
        request.httpMethod = "GET"
        request.setValue("Bearer \(session.token)", forHTTPHeaderField: "Authorization")
        request.setValue(session.workspaceId, forHTTPHeaderField: "X-Workspace-Id")
        let sem = DispatchSemaphore(value: 0)
        var outData: Data?
        var status = 0
        urlSession.dataTask(with: request) { data, response, _ in
            outData = data
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
            sem.signal()
        }.resume()
        if sem.wait(timeout: .now() + readTimeout + 5) == .timedOut || !(200..<300).contains(status) {
            throw RemoteRelayError.remote("远程缩略图拉取失败 HTTP \(status)")
        }
        guard let data = outData, !data.isEmpty else {
            throw RemoteRelayError.invalidResponse
        }
        return data
    }

    static func pullCatalogOriginal(_ session: Session, workId: String, fileName: String, destination: URL) throws {
        let created = try requestJSON(url: "\(session.endpoint)/v1/catalog/pull", method: "POST",
                                      token: session.token,
                                      body: ["workId": workId, "fileName": fileName],
                                      workspaceId: session.workspaceId)
        guard let pull = created["pull"] as? [String: Any],
              let pullId = pull["pullId"] as? String else {
            throw RemoteRelayError.invalidResponse
        }
        let fileURLString = "\(session.endpoint)/v1/catalog/pulls/\(pullId)/file"
        let deadline = Date().addingTimeInterval(45)
        var lastErr: Error?
        var succeeded = false
        while Date() < deadline {
            do {
                try downloadWithRangeAndVerify(urlString: fileURLString, session: session,
                                               destination: destination, expectedBytes: -1, expectedSha256: nil)
                succeeded = true
                break
            } catch {
                lastErr = error
                Thread.sleep(forTimeInterval: 0.65)
            }
        }
        guard succeeded else {
            throw lastErr ?? RemoteRelayError.remote("等待电脑上传高清原图超时")
        }
        _ = try? requestJSON(url: "\(session.endpoint)/v1/catalog/pulls/\(pullId)/ack",
                             method: "POST", token: session.token, body: [:],
                             workspaceId: session.workspaceId)
    }

    static func recordCatalogWorkUsed(_ session: Session, workId: String) throws {
        _ = try requestJSON(url: "\(session.endpoint)/v1/catalog/use-work", method: "POST",
                            token: session.token, body: ["workId": workId],
                            workspaceId: session.workspaceId)
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
                                 timeoutInterval: token == nil ? connectTimeout : readTimeout)
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
        let waitDeadline: DispatchTime = .now() + connectTimeout + readTimeout + 15
        // 【DSH-118】同上：这一条是**所有中继 JSON 请求**的公共出口，
        // 无超时会把整个 relay 链路（列目录 / 取状态 / 提交）拖成假死。
        if semaphore.wait(timeout: waitDeadline) == .timedOut {
            task.cancel()
            invalidateFailedEndpoint(token: token)
            throw RemoteRelayError.endpointUnavailable
        }
        if let error = responseError {
            if isEndpointUnavailable(error) { invalidateFailedEndpoint(token: token) }
            throw error
        }
        if (500..<600).contains(responseStatus) {
            invalidateFailedEndpoint(token: token)
            throw RemoteRelayError.endpointUnavailable
        }
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
    case endpointUnavailable
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
        case .endpointUnavailable: return "远程中继入口暂时不可用。"
        case .remote(let detail): return detail
        }
    }
}
