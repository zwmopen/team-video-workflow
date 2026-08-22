import Foundation
import Network
import UIKit
import Darwin

private let transferHTTPPort: UInt16 = 45833
private let transferDiscoveryPort: UInt16 = 45834

final class IncomingTransferService: P2PTransferEngine.Delegate {
    private let library: WorkLibrary
    private let queue = DispatchQueue(label: "com.zwm.album.incoming-transfer")
    private var tcpListener: NWListener?
    private var udpListener: NWListener?
    private var beaconTimer: DispatchSourceTimer?
    private let remotePresence: RemoteRelayPresence
    private var tasks: [String: IncomingTask] = [:]
    private var activeRelayIds = Set<String>()
    private var remoteInboxTasks = Set<String>()
    private var remoteProcessingTasks = Set<String>()
    private var p2pEngines = [String: P2PTransferEngine]()
    private var isRunning = false
    private var tcpReady = false
    private var udpReady = false

    init(library: WorkLibrary) {
        self.library = library
        let presence = RemoteRelayPresence()
        self.remotePresence = presence
        presence.onInbox = { [weak self] session, tasks in
            guard let self = self else { return }
            self.queue.async { [weak self] in self?.handleRemoteInbox(session, tasks: tasks) }
        }
        presence.onP2PSessions = { [weak self] session, sessions in
            guard let self = self else { return }
            self.queue.async { [weak self] in self?.handleRemoteP2PSessions(session, sessions: sessions) }
        }
    }

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
            self.p2pEngines.values.forEach { $0.cancel() }
            self.p2pEngines.removeAll()
            self.remotePresence.stop()
            self.updateStatus("局域网接收已暂停")
        }
    }

    private func startOnQueue() {
        guard !isRunning else { return }
        do {
            do {
                try RemoteIdentity.ensure()
                UserDefaults.standard.removeObject(forKey: "album.remoteIdentityError.v1")
            } catch {
                // Remote identity is additive groundwork; never block the existing LAN receiver.
                UserDefaults.standard.set(error.localizedDescription, forKey: "album.remoteIdentityError.v1")
            }
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
            remotePresence.start()
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
        timer.setEventHandler { [weak self] in
            self?.broadcastBeacon()
            self?.processRelayQueue()
        }
        timer.resume()
        beaconTimer = timer
    }

    private func handleRemoteInbox(_ session: RemoteRelayClient.Session,
                                   tasks: [RemoteRelayTask]) {
        guard isRunning else { return }
        var fresh = 0
        for task in tasks {
            if remoteInboxTasks.insert(task.transferId).inserted,
               remoteProcessingTasks.insert(task.transferId).inserted {
                fresh += 1
                processRemoteTask(session, task: task)
            }
        }
        while remoteInboxTasks.count > 256 {
            if let first = remoteInboxTasks.first { remoteInboxTasks.remove(first) }
        }
        if fresh > 0 {
            updateStatus("远程作品已接收 \(fresh) 个，正在写入作品库")
        }
    }

    private func processRemoteTask(_ session: RemoteRelayClient.Session,
                                   task: RemoteRelayTask) {
        let transferDirectory = remoteRoot.appendingPathComponent(task.transferId, isDirectory: true)
        do {
            guard task.mode == "plain" else { throw RemoteRelayError.invalidTask }
            guard let root = library.receivingRootURL else {
                throw TransferServiceError.conflict("作品文件夹不可用，请在设置里重新选择")
            }
            try FileManager.default.createDirectory(at: transferDirectory,
                                                    withIntermediateDirectories: true)
            var receivedCount = 0
            if !wasRemoteImported(task.transferId) {
                for object in task.objects {
                    let target = transferDirectory.appendingPathComponent("\(object.index).download")
                    try RemoteRelayClient.downloadObject(session, transferId: task.transferId,
                                                         index: object.index, destination: target,
                                                         expectedBytes: object.bytes,
                                                         expectedSha256: object.sha256)
                    if object.name.lowercased().hasPrefix("album-folder-")
                        && object.name.lowercased().hasSuffix(".zip") {
                        receivedCount += try StoredZipExtractor.extract(target, to: root)
                    } else {
                        let destination = StoredZipExtractor.uniqueDestination(for: object.name, under: root)
                        try FileManager.default.moveItem(at: target, to: destination)
                        receivedCount += 1
                    }
                }
                guard receivedCount > 0 else { throw RemoteRelayError.invalidTask }
                markRemoteImported(task.transferId)
                DispatchQueue.main.async { [weak library] in
                    library?.finishIncomingTransfer(itemCount: receivedCount)
                }
            }
            // The ACK is intentionally last; a failed download or library write
            // keeps the relay object for the next poll.
            try RemoteRelayClient.ack(session, transferId: task.transferId)
            remoteInboxTasks.remove(task.transferId)
            try? FileManager.default.removeItem(at: transferDirectory)
            updateStatus("远程作品已写入作品库，ACK 已完成")
        } catch {
            remoteInboxTasks.remove(task.transferId)
            try? FileManager.default.removeItem(at: transferDirectory)
            updateStatus("远程作品接收失败，未发送 ACK：\(error.localizedDescription)", isError: true)
            TransferNotifications.shared.show("远程接收失败",
                                              body: error.localizedDescription,
                                              id: "remote-failed-\(task.transferId)")
        }
        remoteProcessingTasks.remove(task.transferId)
    }

    private func handleRemoteP2PSessions(_ session: RemoteRelayClient.Session,
                                         sessions: [[String: Any]]) {
        guard isRunning else { return }
        for p2p in sessions {
            guard (p2p["responderDeviceId"] as? String) == session.deviceId,
                  let id = p2p["sessionId"] as? String,
                  let state = p2p["state"] as? String,
                  state != "closed", state != "failed", p2pEngines[id] == nil else { continue }
            let transport = P2PSignalTransport(session: session, sessionId: id)
            guard let engine = P2PTransferEngine.accept(session: p2p, transport: transport,
                                                        delegate: self) else { continue }
            p2pEngines[id] = engine
            updateStatus("正在建立 P2P 直连")
        }
    }

    func p2pEngine(_ engine: P2PTransferEngine,
                   didComplete transfer: P2PTransferEngine.Transfer) throws -> Bool {
        var success = false
        try queue.sync {
            if wasRemoteImported(transfer.transferId) {
                // A previous import may have completed before the sender saw
                // its ACK. Let the engine ACK the duplicate, but do not leave
                // the finished session pinned in the active-engine map.
                p2pEngines = p2pEngines.filter { $0.value !== engine }
                success = true
                return
            }
            guard let root = library.receivingRootURL else {
                throw TransferServiceError.conflict("作品文件夹不可用，请在设置里重新选择")
            }
            var imported = 0
            for (index, object) in transfer.objects.enumerated() {
                let source = transfer.files[index]
                if object.name.lowercased().hasPrefix("album-folder-")
                    && object.name.lowercased().hasSuffix(".zip") {
                    imported += try StoredZipExtractor.extract(source, to: root)
                } else {
                    let destination = StoredZipExtractor.uniqueDestination(for: object.name, under: root)
                    try FileManager.default.moveItem(at: source, to: destination)
                    imported += 1
                }
            }
            guard imported > 0 else { throw RemoteRelayError.invalidTask }
            markRemoteImported(transfer.transferId)
            library.finishIncomingTransfer(itemCount: imported)
            p2pEngines = p2pEngines.filter { $0.value !== engine }
            success = true
            updateStatus("P2P 作品已写入作品库")
        }
        return success
    }

    func p2pEngine(_ engine: P2PTransferEngine, didFail message: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.p2pEngines = self.p2pEngines.filter { $0.value !== engine }
            self.updateStatus("P2P 直传未完成，等待电脑自动切换中继")
        }
    }

    private var remoteRoot: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let root = base.appendingPathComponent("RemoteRelay", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func wasRemoteImported(_ transferId: String) -> Bool {
        Set(UserDefaults.standard.stringArray(forKey: "album.remoteImportedTransfers.v1") ?? [])
            .contains(transferId)
    }

    private func markRemoteImported(_ transferId: String) {
        var values = UserDefaults.standard.stringArray(forKey: "album.remoteImportedTransfers.v1") ?? []
        if !values.contains(transferId) { values.append(transferId) }
        if values.count > 256 { values.removeFirst(values.count - 256) }
        UserDefaults.standard.set(values, forKey: "album.remoteImportedTransfers.v1")
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
        var beacon = "ZWMDS2_HERE|2|\(DeviceIdentity.id)|\(transferHTTPPort)|\(name)|\(model)|\(state)||\(library.advertisedWorkCount)"
        if let counts = library.advertisedWorkCounts {
            // Optional tail fields preserve the old beacon format while
            // exposing the same precise/traffic inventory available at
            // /v2/info to the Windows restock decision.
            beacon += "|\(counts[WorkCategory.conversion] ?? -1)"
            beacon += "|\(counts[WorkCategory.traffic] ?? -1)"
            beacon += "|\(counts[WorkCategory.uncategorized] ?? -1)"
        }
        return Data(beacon.utf8)
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
            if request.method == "POST" && request.path == "/v2/relay-profile" {
                return try saveRelayProfile(request)
            }
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
        var info: [String: Any] = [
            "protocol": 2,
            "deviceId": DeviceIdentity.id,
            "name": DeviceIdentity.name,
            "model": DeviceIdentity.model,
            "iosVersion": UIDevice.current.systemVersion,
            "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
            "relayVersion": 1,
            "relayEnabled": true,
            "port": Int(transferHTTPPort),
            "state": "online",
            "workCount": library.advertisedWorkCount,
            "taskId": ""
        ]
        if let keys = try? RemoteIdentity.publicKeys(),
           let signing = keys["signingPublicKey"] as? [String: Any],
           let agreement = keys["agreementPublicKey"] as? [String: Any],
           let signingX = signing["x"] as? String,
           let signingY = signing["y"] as? String,
           let agreementX = agreement["x"] as? String,
           let agreementY = agreement["y"] as? String {
            // Only public JWK coordinates are exposed to the trusted LAN
            // enrollment request; private keys remain in Keychain.
            info["relaySigningX"] = signingX
            info["relaySigningY"] = signingY
            info["relayAgreementX"] = agreementX
            info["relayAgreementY"] = agreementY
            info["relayEnabled"] = true
        }
        if let counts = library.advertisedWorkCounts {
            // Keep the legacy total and the category aggregate from the same
            // scan. This prevents the PC from seeing a stale total beside
            // fresh precise/traffic counts during the upgrade window.
            info["workCount"] = counts["total"] ?? library.advertisedWorkCount
            info["workCounts"] = counts
        }
        return HTTPResponse(status: 200, object: info)
    }

    private func saveRelayProfile(_ request: HTTPRequest) throws -> HTTPResponse {
        guard let body = request.bodyData,
              let object = try JSONSerialization.jsonObject(with: body) as? [String: Any],
              let endpoint = object["endpoint"] as? String,
              let certificate = object["certificate"] as? [String: Any],
              let signature = object["certificateSignature"] as? String,
              !endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !signature.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw TransferServiceError.badRequest("远程登记资料不完整")
        }
        guard (certificate["deviceId"] as? String) == DeviceIdentity.id else {
            throw TransferServiceError.forbidden("远程登记资料不是发给本机的")
        }
        try RemoteRelayProfile.save(endpoint: endpoint, certificate: certificate,
                                    certificateSignature: signature)
        updateStatus("远程传送已开启，等待电脑连接")
        return HTTPResponse(status: 200, object: ["ok": true])
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
        let relay = RelayTaskInfo(object: object)
        tasks[taskID] = IncomingTask(id: taskID, expectedCount: fileCount,
                                     directory: directory, relay: relay.isRelay ? relay : nil)
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
        if let relay = task.relay, relay.destinationId != DeviceIdentity.id {
            try queueRelay(task)
            tasks.removeValue(forKey: taskID)
            processRelayQueue()
            return HTTPResponse(status: 202, object: [
                "ok": true, "relayStatus": "queued", "messageId": relay.messageId
            ])
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

    private var relayRoot: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let root = base.appendingPathComponent("RelayQueue", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func queueRelay(_ task: IncomingTask) throws {
        guard let relay = task.relay else { return }
        let destination = relayRoot.appendingPathComponent(relay.messageId, isDirectory: true)
        try? FileManager.default.removeItem(at: destination)
        var records: [[String: Any]] = []
        for index in 0..<task.expectedCount {
            guard let file = task.files[index] else {
                throw TransferServiceError.conflict("中转文件不完整")
            }
            records.append(["stored": file.url.lastPathComponent,
                            "name": file.name, "mime": file.mime])
        }
        let relayData = try JSONEncoder().encode(relay)
        let relayObject = try JSONSerialization.jsonObject(with: relayData)
        let manifest: [String: Any] = ["relay": relayObject, "files": records]
        let data = try JSONSerialization.data(withJSONObject: manifest)
        try data.write(to: task.directory.appendingPathComponent("relay.json"),
                       options: .atomic)
        try FileManager.default.moveItem(at: task.directory, to: destination)
        updateStatus("文件已进入中转队列")
    }

    private func processRelayQueue() {
        guard isRunning else { return }
        let directories = (try? FileManager.default.contentsOfDirectory(
            at: relayRoot, includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles])) ?? []
        for directory in directories {
            let manifestURL = directory.appendingPathComponent("relay.json")
            guard let data = try? Data(contentsOf: manifestURL),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let relayObject = object["relay"],
                  let relayData = try? JSONSerialization.data(withJSONObject: relayObject),
                  let relay = try? JSONDecoder().decode(RelayTaskInfo.self, from: relayData) else {
                try? FileManager.default.removeItem(at: directory)
                continue
            }
            if relay.expired {
                try? FileManager.default.removeItem(at: directory)
                updateStatus("一条中转任务已过期并清理", isError: true)
                continue
            }
            let peers = PeerDirectory.shared.peers()
            let next = peers.first(where: { $0.id == relay.destinationId })
                ?? peers.first(where: {
                    $0.id != relay.previousHopId && $0.id != relay.originId
                })
            guard let peer = next, let records = object["files"] as? [[String: Any]] else {
                continue
            }
            guard activeRelayIds.insert(relay.messageId).inserted else { continue }
            let items = records.compactMap { record -> OutgoingItem? in
                guard let stored = record["stored"] as? String,
                      let name = record["name"] as? String else { return nil }
                let url = directory.appendingPathComponent(stored)
                guard FileManager.default.fileExists(atPath: url.path) else { return nil }
                return OutgoingItem(url: url, name: name,
                                    mime: record["mime"] as? String
                                        ?? "application/octet-stream",
                                    temporary: false)
            }
            guard items.count == records.count else {
                activeRelayIds.remove(relay.messageId)
                continue
            }
            OutgoingTransferClient().send(items, to: peer, progress: { _, _ in },
                                          relay: relay.forwarded()) { [weak self] result in
                self?.queue.async {
                    self?.activeRelayIds.remove(relay.messageId)
                    switch result {
                    case .success:
                        try? FileManager.default.removeItem(at: directory)
                        self?.updateStatus("文件中转完成")
                    case .failure(let error):
                        self?.updateStatus("文件等待继续中转：\(error.localizedDescription)",
                                           isError: true)
                    }
                }
            }
        }
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
    let relay: RelayTaskInfo?
    var files: [Int: IncomingFile] = [:]

    init(id: String, expectedCount: Int, directory: URL, relay: RelayTaskInfo?) {
        self.id = id
        self.expectedCount = expectedCount
        self.directory = directory
        self.relay = relay
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
        case 403: reason = "Forbidden"
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

private final class P2PSignalTransport: P2PTransferEngine.SignalTransport {
    private let session: RemoteRelayClient.Session
    private let sessionId: String

    init(session: RemoteRelayClient.Session, sessionId: String) {
        self.session = session
        self.sessionId = sessionId
    }

    func snapshot() throws -> [String: Any] {
        try RemoteRelayClient.p2pSession(session, sessionId: sessionId)
    }

    func send(type: String, data: [String: Any]) throws {
        try RemoteRelayClient.sendP2PSignal(session, sessionId: sessionId,
                                            type: type, data: data)
    }

    func close() {
        let session = self.session
        let sessionId = self.sessionId
        DispatchQueue.global(qos: .utility).async {
            try? RemoteRelayClient.closeP2PSession(session, sessionId: sessionId)
        }
    }
}

enum TransferServiceError: LocalizedError {
    case invalidPort
    case badRequest(String)
    case forbidden(String)
    case notFound(String)
    case conflict(String)
    case unprocessable(String)
    case tooLarge

    var status: Int {
        switch self {
        case .badRequest, .invalidPort: return 400
        case .forbidden: return 403
        case .notFound: return 404
        case .conflict: return 409
        case .tooLarge: return 413
        case .unprocessable: return 422
        }
    }

    var errorDescription: String? {
        switch self {
        case .invalidPort: return "局域网端口无效"
        case .badRequest(let message), .forbidden(let message), .notFound(let message), .conflict(let message),
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
