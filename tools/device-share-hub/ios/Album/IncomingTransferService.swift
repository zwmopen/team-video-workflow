import Foundation
import Network
import UIKit
import Darwin

private let transferHTTPPort: UInt16 = 45833
private let transferDiscoveryPort: UInt16 = 45834

final class IncomingTransferService {
    private let library: WorkLibrary
    private let queue = DispatchQueue(label: "com.zwm.album.incoming-transfer")
    private var tcpListener: NWListener?
    private var udpListener: NWListener?
    private var beaconTimer: DispatchSourceTimer?
    private var tasks: [String: IncomingTask] = [:]
    private var isRunning = false
    private var tcpReady = false
    private var udpReady = false

    init(library: WorkLibrary) { self.library = library }

    func start() {
        queue.async { [weak self] in self?.startOnQueue() }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.isRunning = false
            self.tcpListener?.cancel()
            self.udpListener?.cancel()
            self.beaconTimer?.cancel()
            self.tcpListener = nil
            self.udpListener = nil
            self.beaconTimer = nil
            self.tcpReady = false
            self.udpReady = false
            self.tasks.values.forEach { $0.cleanup() }
            self.tasks.removeAll()
            self.updateStatus("局域网接收已暂停")
        }
    }

    private func startOnQueue() {
        guard !isRunning else { return }
        do {
            let tcpParameters = NWParameters.tcp
            tcpParameters.allowLocalEndpointReuse = true
            let udpParameters = NWParameters.udp
            udpParameters.allowLocalEndpointReuse = true
            guard let httpPort = NWEndpoint.Port(rawValue: transferHTTPPort),
                  let discoveryPort = NWEndpoint.Port(rawValue: transferDiscoveryPort) else {
                throw TransferServiceError.invalidPort
            }
            let tcp = try NWListener(using: tcpParameters, on: httpPort)
            let udp = try NWListener(using: udpParameters, on: discoveryPort)
            tcpReady = false
            udpReady = false
            tcp.stateUpdateHandler = { [weak self] state in self?.handleListenerState(state, name: "文件接收") }
            udp.stateUpdateHandler = { [weak self] state in self?.handleListenerState(state, name: "设备发现") }
            tcp.newConnectionHandler = { [weak self] connection in self?.acceptHTTP(connection) }
            udp.newConnectionHandler = { [weak self] connection in self?.acceptDiscovery(connection) }
            tcp.start(queue: queue)
            udp.start(queue: queue)
            tcpListener = tcp
            udpListener = udp
            isRunning = true
            startBeaconTimer()
            updateStatus("正在开启局域网接收…")
        } catch {
            isRunning = false
            updateStatus("局域网接收启动失败：\(error.localizedDescription)", isError: true)
        }
    }

    private func handleListenerState(_ state: NWListener.State, name: String) {
        switch state {
        case .ready:
            if name == "文件接收" { tcpReady = true } else { udpReady = true }
            if tcpReady && udpReady {
                updateStatus("接收已开启，请保持相册在前台")
            }
        case .failed(let error):
            if name == "文件接收" { tcpReady = false } else { udpReady = false }
            updateStatus("\(name)启动失败：\(error.localizedDescription)", isError: true)
        case .cancelled: break
        default: break
        }
    }

    private func acceptDiscovery(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self = self, let connection = connection else { return }
            guard error == nil, let data = data, let text = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            if text == "ZWMDS2_DISCOVER" {
                connection.send(content: self.beaconData(), completion: .contentProcessed { _ in connection.cancel() })
                return
            }
            if text.hasPrefix("ZWMDS2_HERE|2|"), let host = self.remoteHost(connection.endpoint) {
                let result = PeerDirectory.shared.remember(packet: text, host: host)
                if result.registeredComputer {
                    self.updateStatus("电脑已确认传送权限")
                }
                if result.shouldReply {
                    connection.send(content: self.beaconData(), completion: .contentProcessed { _ in connection.cancel() })
                    return
                }
            }
            connection.cancel()
        }
    }

    private func startBeaconTimer() {
        beaconTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(2000), leeway: .milliseconds(180))
        timer.setEventHandler { [weak self] in self?.broadcastBeacon() }
        timer.resume()
        beaconTimer = timer
    }

    private func broadcastBeacon() {
        guard isRunning else { return }
        let socketHandle = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketHandle >= 0 else { return }
        defer { Darwin.close(socketHandle) }
        var enabled: Int32 = 1
        guard Darwin.setsockopt(socketHandle, SOL_SOCKET, SO_BROADCAST, &enabled,
                                socklen_t(MemoryLayout<Int32>.size)) == 0 else { return }
        let data = beaconData()
        for target in broadcastAddresses() {
            var address = sockaddr_in()
            address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            address.sin_family = sa_family_t(AF_INET)
            address.sin_port = transferDiscoveryPort.bigEndian
            address.sin_addr = target
            data.withUnsafeBytes { bytes in
                withUnsafePointer(to: &address) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
                        _ = Darwin.sendto(socketHandle, bytes.baseAddress, data.count, 0, socketAddress,
                                          socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
    }

    private func broadcastAddresses() -> [in_addr] {
        var result: [in_addr] = []
        var seen = Set<UInt32>()
        func append(_ value: UInt32) {
            if seen.insert(value).inserted { result.append(in_addr(s_addr: value)) }
        }
        "255.255.255.255".withCString { append(Darwin.inet_addr($0)) }
        var first: UnsafeMutablePointer<ifaddrs>?
        guard Darwin.getifaddrs(&first) == 0, let start = first else { return result }
        defer { Darwin.freeifaddrs(start) }
        var current: UnsafeMutablePointer<ifaddrs>? = start
        while let item = current {
            let interface = item.pointee
            if let rawAddress = interface.ifa_addr, let rawMask = interface.ifa_netmask,
               rawAddress.pointee.sa_family == sa_family_t(AF_INET),
               (interface.ifa_flags & UInt32(IFF_UP)) != 0,
               (interface.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 {
                let address = rawAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                let mask = rawMask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                append(address | ~mask)
            }
            current = interface.ifa_next
        }
        return result
    }

    private func remoteHost(_ endpoint: NWEndpoint) -> String? {
        guard case let .hostPort(host, _) = endpoint else { return nil }
        return "\(host)".trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
    }

    private func beaconData() -> Data {
        let name = Data(DeviceIdentity.name.utf8).base64URL
        let model = Data(DeviceIdentity.model.utf8).base64URL
        let state = Data("online".utf8).base64URL
        return Data("ZWMDS2_HERE|2|\(DeviceIdentity.id)|\(transferHTTPPort)|\(name)|\(model)|\(state)||\(library.advertisedWorkCount)".utf8)
    }

    private func acceptHTTP(_ connection: NWConnection) {
        let reader = HTTPRequestReader(connection: connection, queue: queue) { [weak self] request in
            guard let self = self else { return HTTPResponse(status: 503, message: "服务已停止") }
            return self.handle(request)
        }
        reader.start()
    }

    private func handle(_ request: HTTPRequest) -> HTTPResponse {
        do {
            if request.method == "GET" && request.path == "/v2/info" { return infoResponse() }
            if request.method == "POST" && request.path == "/v2/tasks" { return try createTask(request) }
            let pieces = request.path.split(separator: "/").map(String.init)
            if request.method == "PUT", pieces.count == 5, pieces[0] == "v2", pieces[1] == "tasks",
               pieces[3] == "files", let index = Int(pieces[4]) {
                return try uploadFile(taskID: pieces[2], index: index, request: request)
            }
            if request.method == "POST", pieces.count == 3, pieces[0] == "v2", pieces[1] == "tasks" {
                if pieces[2].hasSuffix("commit") || pieces[2].hasSuffix("cancel") {
                    return HTTPResponse(status: 404, message: "请求路径不存在")
                }
            }
            if request.method == "POST", pieces.count == 4, pieces[0] == "v2", pieces[1] == "tasks",
               pieces[3] == "commit" {
                return try commitTask(taskID: pieces[2])
            }
            if request.method == "POST", pieces.count == 4, pieces[0] == "v2", pieces[1] == "tasks",
               pieces[3] == "cancel" {
                return cancelTask(taskID: pieces[2])
            }
            return HTTPResponse(status: 404, message: "请求路径不存在")
        } catch let error as TransferServiceError {
            TransferNotifications.shared.show("接收失败", body: error.localizedDescription,
                                              id: "incoming-failed-\(UUID().uuidString)")
            return HTTPResponse(status: error.status, message: error.localizedDescription)
        } catch {
            updateStatus("接收失败：\(error.localizedDescription)", isError: true)
            TransferNotifications.shared.show("接收失败", body: error.localizedDescription,
                                              id: "incoming-failed-\(UUID().uuidString)")
            return HTTPResponse(status: 500, message: error.localizedDescription)
        }
    }

    private func infoResponse() -> HTTPResponse {
        return HTTPResponse(status: 200, object: [
            "protocol": 2,
            "deviceId": DeviceIdentity.id,
            "name": DeviceIdentity.name,
            "model": DeviceIdentity.model,
            "iosVersion": UIDevice.current.systemVersion,
            "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
            "port": Int(transferHTTPPort),
            "state": "online",
            "workCount": library.advertisedWorkCount,
            "taskId": ""
        ])
    }

    private func createTask(_ request: HTTPRequest) throws -> HTTPResponse {
        guard let body = request.bodyData,
              let object = try JSONSerialization.jsonObject(with: body) as? [String: Any],
              let taskID = object["taskId"] as? String,
              let fileCount = object["fileCount"] as? Int else {
            throw TransferServiceError.badRequest("任务信息不完整")
        }
        guard isSafeIdentifier(taskID), fileCount > 0, fileCount <= 100 else {
            throw TransferServiceError.badRequest("任务编号或文件数量无效")
        }
        if let previous = tasks.removeValue(forKey: taskID) { previous.cleanup() }
        let directory = incomingRoot.appendingPathComponent(taskID, isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        tasks[taskID] = IncomingTask(id: taskID, expectedCount: fileCount, directory: directory)
        updateStatus("正在接收 \(fileCount) 个项目…")
        TransferNotifications.shared.show("正在向你发送", body: "准备接收 \(fileCount) 个项目",
                                          id: "incoming-start-\(taskID)")
        return HTTPResponse(status: 201, object: ["ok": true, "taskId": taskID])
    }

    private func uploadFile(taskID: String, index: Int, request: HTTPRequest) throws -> HTTPResponse {
        guard let task = tasks[taskID] else { throw TransferServiceError.notFound("接收任务不存在，请重试") }
        guard index >= 0, index < task.expectedCount, let temporary = request.bodyFileURL else {
            throw TransferServiceError.badRequest("文件序号无效")
        }
        let encodedName = request.headers["x-file-name"] ?? "file-\(index)"
        let decoded = encodedName.removingPercentEncoding ?? encodedName
        let name = safeFileName(decoded, fallback: "file-\(index)")
        if let expected = request.headers["x-file-sha256"]?.lowercased(), !expected.isEmpty {
            let actual = try SHA256.fileHex(temporary)
            guard actual == expected else {
                try? FileManager.default.removeItem(at: temporary)
                throw TransferServiceError.unprocessable("“\(name)”校验失败，请重新传送")
            }
        }
        let destination = task.directory.appendingPathComponent("\(index).part")
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: temporary, to: destination)
        task.files[index] = IncomingFile(name: name,
                                         mime: request.headers["x-file-mime"] ?? "application/octet-stream",
                                         url: destination)
        updateStatus("正在接收 \(task.files.count)/\(task.expectedCount)：\(name)")
        return HTTPResponse(status: 200, object: ["ok": true, "index": index])
    }

    private func commitTask(taskID: String) throws -> HTTPResponse {
        guard let task = tasks[taskID] else { throw TransferServiceError.notFound("接收任务不存在，请重试") }
        guard task.files.count == task.expectedCount else {
            throw TransferServiceError.conflict("文件尚未全部接收，请稍后重试")
        }
        guard let root = library.receivingRootURL else {
            throw TransferServiceError.conflict("作品文件夹不可用，请在设置里重新选择")
        }
        var receivedCount = 0
        for index in 0..<task.expectedCount {
            guard let file = task.files[index] else { throw TransferServiceError.conflict("文件不完整") }
            if file.name.lowercased().hasPrefix("album-folder-") && file.name.lowercased().hasSuffix(".zip") {
                receivedCount += try StoredZipExtractor.extract(file.url, to: root)
            } else {
                let destination = StoredZipExtractor.uniqueDestination(for: file.name, under: root)
                try FileManager.default.moveItem(at: file.url, to: destination)
                receivedCount += 1
            }
        }
        tasks.removeValue(forKey: taskID)
        task.cleanup()
        DispatchQueue.main.async { [weak library] in library?.finishIncomingTransfer(itemCount: receivedCount) }
        TransferNotifications.shared.show("接收完成", body: "已收到 \(receivedCount) 个项目",
                                          id: "incoming-complete-\(taskID)")
        return HTTPResponse(status: 200, object: ["ok": true, "received": receivedCount])
    }

    private func cancelTask(taskID: String) -> HTTPResponse {
        if let task = tasks.removeValue(forKey: taskID) { task.cleanup() }
        updateStatus("传送已取消")
        return HTTPResponse(status: 200, object: ["ok": true])
    }

    private var incomingRoot: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let root = base.appendingPathComponent("IncomingTransfer", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func updateStatus(_ text: String, isError: Bool = false) {
        UserDefaults.standard.set(text, forKey: "album.lastNetworkStatus.v1")
        DispatchQueue.main.async { [weak library] in library?.setNetworkStatus(text, isError: isError) }
    }

    private func isSafeIdentifier(_ value: String) -> Bool {
        guard !value.isEmpty, value.count <= 128 else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || "-_.".unicodeScalars.contains($0)
        }
    }

    private func safeFileName(_ value: String, fallback: String) -> String {
        let normalized = value.replacingOccurrences(of: "\\", with: "/")
        let candidate = normalized.split(separator: "/").last.map(String.init) ?? fallback
        let cleaned = candidate.replacingOccurrences(of: ":", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty || cleaned == "." || cleaned == ".." ? fallback : String(cleaned.prefix(240))
    }
}

private final class IncomingTask {
    let id: String
    let expectedCount: Int
    let directory: URL
    var files: [Int: IncomingFile] = [:]

    init(id: String, expectedCount: Int, directory: URL) {
        self.id = id
        self.expectedCount = expectedCount
        self.directory = directory
    }

    func cleanup() { try? FileManager.default.removeItem(at: directory) }
}

private struct IncomingFile {
    let name: String
    let mime: String
    let url: URL
}

struct HTTPRequest {
    let method: String
    let path: String
    let headers: [String: String]
    let bodyData: Data?
    let bodyFileURL: URL?
}

struct HTTPResponse {
    let status: Int
    let body: Data

    init(status: Int, object: [String: Any]) {
        self.status = status
        self.body = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{\"ok\":false}".utf8)
    }

    init(status: Int, message: String) {
        self.init(status: status, object: ["ok": false, "error": message])
    }

    var wireData: Data {
        let reason: String
        switch status {
        case 200: reason = "OK"
        case 201: reason = "Created"
        case 400: reason = "Bad Request"
        case 404: reason = "Not Found"
        case 409: reason = "Conflict"
        case 413: reason = "Payload Too Large"
        case 422: reason = "Unprocessable Entity"
        default: reason = "Internal Server Error"
        }
        let header = "HTTP/1.1 \(status) \(reason)\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        var result = Data(header.utf8)
        result.append(body)
        return result
    }
}

final class HTTPRequestReader {
    private let connection: NWConnection
    private let queue: DispatchQueue
    private let handler: (HTTPRequest) -> HTTPResponse
    private var headerBuffer = Data()
    private var method = ""
    private var path = ""
    private var headers: [String: String] = [:]
    private var expectedLength: UInt64 = 0
    private var receivedLength: UInt64 = 0
    private var bodyData = Data()
    private var bodyFileURL: URL?
    private var bodyFile: FileHandle?
    private var parsedHeaders = false
    private var finished = false
    private var keepAlive: HTTPRequestReader?

    init(connection: NWConnection, queue: DispatchQueue,
         handler: @escaping (HTTPRequest) -> HTTPResponse) {
        self.connection = connection
        self.queue = queue
        self.handler = handler
    }

    func start() {
        keepAlive = self
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.cleanup() }
        }
        connection.start(queue: queue)
        receiveNext()
    }

    private func receiveNext() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1024 * 1024) {
            [weak self] data, _, isComplete, error in
            guard let self = self, !self.finished else { return }
            if let error = error { self.fail("连接中断：\(error.localizedDescription)"); return }
            do {
                if let data = data, !data.isEmpty { try self.consume(data) }
                if self.finished { return }
                if isComplete { self.fail("文件没有接收完整"); return }
                self.receiveNext()
            } catch let error as TransferServiceError {
                self.send(HTTPResponse(status: error.status, message: error.localizedDescription))
            } catch {
                self.send(HTTPResponse(status: 400, message: error.localizedDescription))
            }
        }
    }

    private func consume(_ data: Data) throws {
        if !parsedHeaders {
            headerBuffer.append(data)
            guard headerBuffer.count <= 64 * 1024 else { throw TransferServiceError.badRequest("请求头过大") }
            let marker = Data("\r\n\r\n".utf8)
            guard let range = headerBuffer.range(of: marker) else { return }
            let headerPart = headerBuffer.subdata(in: 0..<range.lowerBound)
            let remainder = headerBuffer.subdata(in: range.upperBound..<headerBuffer.endIndex)
            try parseHeaders(headerPart)
            headerBuffer.removeAll(keepingCapacity: false)
            parsedHeaders = true
            if !remainder.isEmpty { try consumeBody(remainder) }
            if expectedLength == 0 { finishRequest() }
            return
        }
        try consumeBody(data)
    }

    private func parseHeaders(_ data: Data) throws {
        guard let text = String(data: data, encoding: .utf8) else {
            throw TransferServiceError.badRequest("请求头编码无效")
        }
        let lines = text.components(separatedBy: "\r\n")
        let requestLine = lines.first?.split(separator: " ") ?? []
        guard requestLine.count >= 2 else { throw TransferServiceError.badRequest("请求格式无效") }
        method = String(requestLine[0]).uppercased()
        path = String(requestLine[1])
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespacesAndNewlines)
            headers[name] = value
        }
        guard let contentLength = UInt64(headers["content-length"] ?? "0") else {
            throw TransferServiceError.badRequest("文件长度无效")
        }
        expectedLength = contentLength
        guard expectedLength <= 4 * 1024 * 1024 * 1024 else { throw TransferServiceError.tooLarge }
        if method == "PUT" {
            let base = FileManager.default.temporaryDirectory.appendingPathComponent("AlbumHTTP", isDirectory: true)
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            let url = base.appendingPathComponent(UUID().uuidString + ".part")
            FileManager.default.createFile(atPath: url.path, contents: nil)
            bodyFileURL = url
            bodyFile = try FileHandle(forWritingTo: url)
        } else if expectedLength > 2 * 1024 * 1024 {
            throw TransferServiceError.tooLarge
        }
    }

    private func consumeBody(_ data: Data) throws {
        guard receivedLength + UInt64(data.count) <= expectedLength else {
            throw TransferServiceError.badRequest("收到的数据超过声明长度")
        }
        if let file = bodyFile { file.write(data) } else { bodyData.append(data) }
        receivedLength += UInt64(data.count)
        if receivedLength == expectedLength { finishRequest() }
    }

    private func finishRequest() {
        bodyFile?.closeFile()
        bodyFile = nil
        let request = HTTPRequest(method: method, path: path, headers: headers,
                                  bodyData: bodyFileURL == nil ? bodyData : nil,
                                  bodyFileURL: bodyFileURL)
        send(handler(request))
    }

    private func fail(_ message: String) { send(HTTPResponse(status: 400, message: message)) }

    private func send(_ response: HTTPResponse) {
        guard !finished else { return }
        finished = true
        bodyFile?.closeFile()
        bodyFile = nil
        if let url = bodyFileURL { try? FileManager.default.removeItem(at: url) }
        connection.send(content: response.wireData, completion: .contentProcessed { [weak self] _ in
            self?.connection.cancel()
            self?.keepAlive = nil
        })
    }

    private func cleanup() {
        bodyFile?.closeFile()
        bodyFile = nil
        if let url = bodyFileURL { try? FileManager.default.removeItem(at: url) }
        connection.cancel()
        keepAlive = nil
    }
}

enum TransferServiceError: LocalizedError {
    case invalidPort
    case badRequest(String)
    case notFound(String)
    case conflict(String)
    case unprocessable(String)
    case tooLarge

    var status: Int {
        switch self {
        case .badRequest, .invalidPort: return 400
        case .notFound: return 404
        case .conflict: return 409
        case .tooLarge: return 413
        case .unprocessable: return 422
        }
    }

    var errorDescription: String? {
        switch self {
        case .invalidPort: return "局域网端口无效"
        case .badRequest(let message), .notFound(let message), .conflict(let message),
             .unprocessable(let message): return message
        case .tooLarge: return "文件过大，请分批传送"
        }
    }
}

private extension Data {
    var base64URL: String {
        return base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
