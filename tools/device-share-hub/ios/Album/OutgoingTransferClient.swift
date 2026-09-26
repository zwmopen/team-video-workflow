import Foundation
import MobileCoreServices

struct OutgoingItem {
    let url: URL
    let name: String
    let mime: String
    let temporary: Bool
}

final class OutgoingTransferClient: NSObject, URLSessionTaskDelegate {
    typealias Progress = (Int, String) -> Void
    // 【DSH-118】等待对方响应的兜底上限。必须大于 URLSession 的请求超时（30s），
    // 又不能太大 —— 否则用户要盯着转圈等很久才知道失败。取 45 秒。
    private static let timeoutSeconds: TimeInterval = 45
    private static var requestWaitTimeout: DispatchTimeInterval {
        .milliseconds(Int(timeoutSeconds * 1000))
    }
    private let queue = DispatchQueue(label: "com.zwm.album.outgoing-transfer")
    private var progress: Progress?
    private var completedBytes: Int64 = 0
    private var totalBytes: Int64 = 1
    private var peerName = ""
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60 * 60
        let delegateQueue = OperationQueue()
        delegateQueue.maxConcurrentOperationCount = 1
        return URLSession(configuration: configuration, delegate: self, delegateQueue: delegateQueue)
    }()

    func send(_ items: [OutgoingItem], to peer: TransferPeer, progress: @escaping Progress,
              relay: RelayTaskInfo? = nil,
              completion: @escaping (Result<Void, Error>) -> Void) {
        queue.async {
            do {
                guard !items.isEmpty else { throw OutgoingError.noFiles }
                self.progress = progress
                self.peerName = peer.name
                self.totalBytes = try items.reduce(0) { $0 + Int64(try $1.url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
                if self.totalBytes <= 0 { self.totalBytes = 1 }
                self.completedBytes = 0
                let taskID = "ios-\(UUID().uuidString.lowercased())"
                var taskObject: [String: Any] = [
                    "taskId": taskID, "text": "", "fileCount": items.count
                ]
                relay?.add(to: &taskObject)
                let json = try JSONSerialization.data(withJSONObject: taskObject)
                try self.perform(peer: peer, method: "POST", path: "/v2/tasks", body: json, file: nil,
                                 headers: ["Content-Type": "application/json"])
                do {
                    for (index, item) in items.enumerated() {
                        let size = Int64(try item.url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
                        let sha = try SHA256.fileHex(item.url)
                        try self.perform(peer: peer, method: "PUT", path: "/v2/tasks/\(taskID)/files/\(index)",
                                         body: nil, file: item.url, headers: [
                                            "Content-Type": item.mime,
                                            "X-File-Name": self.percentEncode(item.name),
                                            "X-File-Mime": item.mime,
                                            "X-File-Sha256": sha
                                         ])
                        self.completedBytes += size
                    }
                    try self.perform(peer: peer, method: "POST", path: "/v2/tasks/\(taskID)/commit",
                                     body: Data(), file: nil, headers: ["Content-Type": "text/plain"])
                } catch {
                    try? self.perform(peer: peer, method: "POST", path: "/v2/tasks/\(taskID)/cancel",
                                      body: Data(), file: nil, headers: ["Content-Type": "text/plain"])
                    throw error
                }
                DispatchQueue.main.async { progress(100, "WiFi 传送完成 · “\(peer.name)”"); completion(.success(())) }
            } catch {
                DispatchQueue.main.async { completion(.failure(error)) }
            }
            items.filter { $0.temporary }.forEach { try? FileManager.default.removeItem(at: $0.url) }
        }
    }

    private func perform(peer: TransferPeer, method: String, path: String, body: Data?, file: URL?,
                         headers: [String: String]) throws {
        guard let url = URL(string: "http://\(peer.host):\(peer.port)\(path)") else { throw OutgoingError.invalidDevice }
        var request = URLRequest(url: url)
        request.httpMethod = method
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let semaphore = DispatchSemaphore(value: 0)
        var resultError: Error?
        var status = 0
        var responseBody = Data()
        let completion: (Data?, URLResponse?, Error?) -> Void = { data, response, error in
            responseBody = data ?? Data()
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
            resultError = error
            semaphore.signal()
        }
        let task: URLSessionTask
        if let file = file { task = session.uploadTask(with: request, fromFile: file, completionHandler: completion) }
        else { request.httpBody = body; task = session.dataTask(with: request, completionHandler: completion) }
        task.resume()
        // 【DSH-118】原本是 `semaphore.wait()` 无超时：对方设备中途断连 / 息屏 /
        // 切后台导致回调永远不来时，这个线程就永久挂住 —— 传送界面一直转圈，
        // 既不报错也不超时，只能杀 App。给一个比请求超时略宽的兜底窗口，
        // 超时即取消任务并显式抛错（用户能看见「对方无响应」，而不是无限等待）。
        let waitDeadline: DispatchTime = .now() + Self.requestWaitTimeout
        if semaphore.wait(timeout: waitDeadline) == .timedOut {
            task.cancel()
            throw OutgoingError.remote("对方 \(Self.timeoutSeconds) 秒无响应，已取消本次请求")
        }
        if let error = resultError { throw error }
        guard (200..<300).contains(status) else {
            let detail = String(data: responseBody.prefix(4096), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            throw OutgoingError.remote(detail?.isEmpty == false ? detail! : "对方返回错误 \(status)")
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard task.originalRequest?.httpMethod == "PUT" else { return }
        let percent = Int(min(100, (completedBytes + totalBytesSent) * 100 / totalBytes))
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.progress?(percent, "WiFi 传送中 · “\(self.peerName)” \(percent)%")
        }
    }

    private func percentEncode(_ value: String) -> String {
        return value.addingPercentEncoding(withAllowedCharacters: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? "file"
    }
}

enum OutgoingError: LocalizedError {
    case noFiles, invalidDevice, remote(String)
    var errorDescription: String? {
        switch self {
        case .noFiles: return "没有选择文件"
        case .invalidDevice: return "接收设备地址无效"
        case .remote(let detail): return detail
        }
    }
}
