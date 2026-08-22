import Foundation
import WebRTC

/// Authenticated responder for the album-transfer-v1 DataChannel.
final class P2PTransferEngine: NSObject {
    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()
    private static let label = "album-transfer-v1"
    private static let maxChunk = 48 * 1024
    private static let maxBytes: Int64 = 20 * 1024 * 1024 * 1024
    private static let ackFlushDelay: DispatchTimeInterval = .milliseconds(500)

    struct ObjectInfo {
        let index: Int
        let bytes: Int64
        let sha256: String
        let name: String
        let mime: String
    }

    struct Transfer {
        let transferId: String
        let senderDeviceId: String
        let recipientDeviceId: String
        let objects: [ObjectInfo]
        let files: [URL]
    }

    protocol SignalTransport: AnyObject {
        func snapshot() throws -> [String: Any]
        func send(type: String, data: [String: Any]) throws
    }

    protocol Delegate: AnyObject {
        func p2pEngine(_ engine: P2PTransferEngine, didComplete transfer: Transfer) throws -> Bool
        func p2pEngine(_ engine: P2PTransferEngine, didFail message: String)
    }

    private let session: [String: Any]
    private let transport: SignalTransport
    private weak var delegate: Delegate?
    private let queue = DispatchQueue(label: "com.zwm.album.p2p-transfer")
    private let constraints = RTCMediaConstraints(
        mandatoryConstraints: nil,
        optionalConstraints: ["DtlsSrtpKeyAgreement": kRTCMediaConstraintsValueTrue]
    )
    private var peer: RTCPeerConnection!
    private var dataChannel: RTCDataChannel?
    private var timer: DispatchSourceTimer?
    private var appliedSignals = Set<String>()
    private var pendingCandidates = [RTCIceCandidate]()
    private var transferDirectory: URL?
    private var fileHandles: [Int: FileHandle] = [:]
    private var receivedBytes: [Int: Int64] = [:]
    private var objects: [ObjectInfo] = []
    private var transferId = ""
    private var senderDeviceId = ""
    private var recipientDeviceId = ""
    private var finished = false
    private var remoteDescriptionSet = false

    private init(session: [String: Any], transport: SignalTransport, delegate: Delegate) {
        self.session = session
        self.transport = transport
        self.delegate = delegate
        super.init()
    }

    @discardableResult
    static func accept(session: [String: Any], transport: SignalTransport,
                       delegate: Delegate) -> P2PTransferEngine? {
        let engine = P2PTransferEngine(session: session, transport: transport, delegate: delegate)
        engine.start()
        return engine.finished ? nil : engine
    }

    func cancel() {
        queue.async { [weak self] in
            guard let self = self, !self.finished else { return }
            self.finished = true
            self.shutdown(removeFiles: true)
        }
    }

    private func start() {
        let configuration = RTCConfiguration()
        configuration.iceServers = [RTCIceServer(urlStrings: ["stun:stun.cloudflare.com:3478"])]
        configuration.sdpSemantics = .unifiedPlan
        configuration.continualGatheringPolicy = .gatherContinually
        let factory = Self.factory
        peer = factory.peerConnection(with: configuration, constraints: constraints, delegate: self)
        guard peer != nil else { fail("无法创建 P2P 连接"); return }
        timer = DispatchSource.makeTimerSource(queue: queue)
        timer?.schedule(deadline: .now(), repeating: .milliseconds(100), leeway: .milliseconds(20))
        timer?.setEventHandler { [weak self] in self?.pollSignals() }
        timer?.resume()
    }

    private func pollSignals() {
        guard !finished else { return }
        do {
            let snapshot = try transport.snapshot()
            guard let signals = snapshot["signals"] as? [[String: Any]] else { return }
            for signal in signals {
                let type = signal["type"] as? String ?? ""
                let from = signal["fromDeviceId"] as? String ?? ""
                let sentAt = int64(signal["sentAt"]) ?? 0
                let key = "\(from):\(sentAt):\(type)"
                guard appliedSignals.insert(key).inserted,
                      let data = signal["data"] as? [String: Any] else { continue }
                if type == "offer" { applyOffer(data) }
                else if type == "ice" { applyIce(data) }
            }
        } catch {
            if peer.iceConnectionState == .failed { fail("P2P 信令连接失败") }
        }
    }

    private func applyOffer(_ data: [String: Any]) {
        guard let sdp = data["sdp"] as? String, !sdp.isEmpty else {
            fail("P2P offer 为空"); return
        }
        peer.setRemoteDescription(RTCSessionDescription(type: .offer, sdp: sdp)) { [weak self] error in
            guard let self = self else { return }
            if error != nil {
                self.fail("P2P offer 无法应用")
            } else {
                self.remoteDescriptionSet = true
                self.pendingCandidates.forEach { self.peer.add($0) }
                self.pendingCandidates.removeAll()
                self.createAnswer()
            }
        }
    }

    private func createAnswer() {
        peer.answer(for: constraints) { [weak self] description, error in
            guard let self = self else { return }
            guard let description = description, error == nil else {
                self.fail("P2P answer 创建失败"); return
            }
            self.peer.setLocalDescription(description) { [weak self] error in
                guard let self = self else { return }
                guard error == nil, let local = self.peer.localDescription else {
                    self.fail("P2P answer 设置失败"); return
                }
                do {
                    try self.transport.send(type: "answer", data: [
                        "type": "answer", "sdp": local.sdp
                    ])
                } catch { self.fail("P2P answer 信令发送失败") }
            }
        }
    }

    private func applyIce(_ data: [String: Any]) {
        guard let candidate = data["candidate"] as? String,
              let mid = data["mid"] as? String else { return }
        let value = RTCIceCandidate(sdp: candidate,
                                    sdpMLineIndex: Int32(int(data["mLineIndex"]) ?? 0),
                                    sdpMid: mid)
        if remoteDescriptionSet { peer.add(value) }
        else { pendingCandidates.append(value) }
    }

    private func handleText(_ data: Data) throws {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              int(object["v"]) == 1,
              let kind = object["kind"] as? String else { throw RemoteRelayError.invalidTask }
        if kind == "manifest" { try readManifest(object) }
        else if kind == "complete" { try complete(object["transferId"] as? String ?? "") }
    }

    private func readManifest(_ message: [String: Any]) throws {
        transferId = try safeId(message["transferId"] as? String)
        senderDeviceId = try safeId(message["senderDeviceId"] as? String)
        recipientDeviceId = try safeId(message["recipientDeviceId"] as? String)
        guard senderDeviceId == (session["initiatorDeviceId"] as? String),
              recipientDeviceId == (session["responderDeviceId"] as? String),
              let raw = message["objects"] as? [[String: Any]], !raw.isEmpty,
              raw.count <= 1_000 else { throw RemoteRelayError.invalidTask }
        var indexes = Set<Int>()
        var total: Int64 = 0
        objects = try raw.map { item in
            guard let index = int(item["index"]), index >= 0, indexes.insert(index).inserted,
                  let bytes = int64(item["bytes"]), bytes > 0,
                  let sha256 = item["sha256"] as? String,
                  sha256.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil,
                  let name = item["name"] as? String, safeName(name) else {
                throw RemoteRelayError.invalidTask
            }
            total = try adding(total, bytes)
            return ObjectInfo(index: index, bytes: bytes, sha256: sha256.lowercased(), name: name,
                              mime: item["mime"] as? String ?? "application/octet-stream")
        }
        guard total <= Self.maxBytes, int64(message["totalBytes"]) ?? total == total else {
            throw RemoteRelayError.invalidTask
        }
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("P2P", isDirectory: true)
            .appendingPathComponent(transferId, isDirectory: true)
        try? FileManager.default.removeItem(at: root)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        transferDirectory = root
        receivedBytes = Dictionary(uniqueKeysWithValues: objects.map { ($0.index, Int64(0)) })
    }

    private func handleBinary(_ data: Data) throws {
        guard data.count > 20, data.count <= 20 + Self.maxChunk,
              data.prefix(4) == Data([0x44, 0x53, 0x48, 0x50]), data[4] == 1, data[5] == 1,
              data[6] == 0, data[7] == 0 else { throw RemoteRelayError.invalidTask }
        let index = Int(data.readUInt32BE(at: 8))
        let offset = Int64(data.readUInt64BE(at: 12))
        guard let object = objects.first(where: { $0.index == index }),
              let expected = receivedBytes[index], expected == offset,
              offset + Int64(data.count - 20) <= object.bytes,
              let root = transferDirectory else { throw RemoteRelayError.invalidTask }
        let url = root.appendingPathComponent("\(index).part")
        if fileHandles[index] == nil {
            FileManager.default.createFile(atPath: url.path, contents: nil)
            fileHandles[index] = try FileHandle(forWritingTo: url)
        }
        let payload = data.subdata(in: 20..<data.count)
        fileHandles[index]?.seek(toFileOffset: UInt64(offset))
        fileHandles[index]?.write(payload)
        receivedBytes[index] = offset + Int64(payload.count)
    }

    private func complete(_ id: String) throws {
        guard id == transferId, let root = transferDirectory else { throw RemoteRelayError.invalidTask }
        for handle in fileHandles.values { handle.closeFile() }
        fileHandles.removeAll()
        let files = try objects.map { object -> URL in
            let file = root.appendingPathComponent("\(object.index).part")
            guard FileManager.default.fileExists(atPath: file.path),
                  (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) == object.bytes,
                  try SHA256.fileHex(file).caseInsensitiveCompare(object.sha256) == .orderedSame else {
                throw RemoteRelayError.invalidTask
            }
            return file
        }
        let transfer = Transfer(transferId: transferId, senderDeviceId: senderDeviceId,
                                recipientDeviceId: recipientDeviceId, objects: objects, files: files)
        guard try delegate?.p2pEngine(self, didComplete: transfer) == true else {
            throw RemoteRelayError.invalidTask
        }
        let ack: [String: Any] = ["v": 1, "kind": "ack", "transferId": transferId,
                                   "ok": true, "objects": objects.count,
                                   "bytes": objects.reduce(0) { $0 + $1.bytes }]
        try sendJSON(ack)
        finished = true
        // Do not close SCTP immediately after sendData: the sender needs the
        // ACK to decide not to retry through the HTTPS relay. Also remove the
        // temporary cache after the existing library import has completed.
        queue.asyncAfter(deadline: .now() + Self.ackFlushDelay) { [weak self] in
            self?.shutdown(removeFiles: true)
        }
    }

    private func sendJSON(_ object: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: object, options: [])
        guard let channel = dataChannel, channel.readyState == .open else { throw RemoteRelayError.invalidTask }
        guard channel.sendData(RTCDataBuffer(data: data, isBinary: false)) else {
            throw RemoteRelayError.invalidTask
        }
    }

    private func fail(_ message: String) {
        guard !finished else { return }
        finished = true
        delegate?.p2pEngine(self, didFail: message)
        shutdown(removeFiles: true)
    }

    private func shutdown(removeFiles: Bool) {
        timer?.cancel(); timer = nil
        fileHandles.values.forEach { $0.closeFile() }
        fileHandles.removeAll()
        dataChannel?.close(); dataChannel = nil
        peer?.close()
        if removeFiles, let root = transferDirectory { try? FileManager.default.removeItem(at: root) }
    }

    private func safeId(_ value: String?) throws -> String {
        guard let value = value, value.range(of: "^[A-Za-z0-9_-]{8,128}$", options: .regularExpression) != nil else {
            throw RemoteRelayError.invalidTask
        }
        return value
    }

    private func safeName(_ value: String) -> Bool {
        !value.isEmpty && value.count <= 240 && value != "." && value != ".."
            && !value.contains("/") && !value.contains("\\") && !value.contains("\0")
    }

    private func int(_ value: Any?) -> Int? { (value as? NSNumber)?.intValue }
    private func int64(_ value: Any?) -> Int64? { (value as? NSNumber)?.int64Value }
    private func adding(_ left: Int64, _ right: Int64) throws -> Int64 {
        guard right >= 0, left <= Int64.max - right else { throw RemoteRelayError.invalidTask }
        return left + right
    }
}

extension P2PTransferEngine: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        if newState == .failed { fail("P2P ICE 连接失败") }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        do {
            try transport.send(type: "ice", data: ["candidate": candidate.sdp,
                                                       "mid": candidate.sdpMid ?? "",
                                                       "mLineIndex": candidate.sdpMLineIndex])
        } catch { fail("P2P ICE 信令发送失败") }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        guard dataChannel.label == Self.label else { dataChannel.close(); return }
        self.dataChannel = dataChannel
        dataChannel.delegate = self
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        if newState == .failed { fail("P2P 连接失败") }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {}
}

extension P2PTransferEngine: RTCDataChannelDelegate {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        if dataChannel.readyState == .closed && !finished { fail("P2P 数据通道已关闭") }
    }

    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        queue.async { [weak self] in
            guard let self = self, !self.finished else { return }
            do {
                if buffer.isBinary { try self.handleBinary(buffer.data) }
                else { try self.handleText(buffer.data) }
            } catch { self.fail("P2P 文件校验失败") }
        }
    }
}

private extension Data {
    func readUInt32BE(at offset: Int) -> UInt32 {
        UInt32(self[offset]) << 24 | UInt32(self[offset + 1]) << 16
            | UInt32(self[offset + 2]) << 8 | UInt32(self[offset + 3])
    }

    func readUInt64BE(at offset: Int) -> UInt64 {
        var result: UInt64 = 0
        for index in 0..<8 { result = (result << 8) | UInt64(self[offset + index]) }
        return result
    }
}
