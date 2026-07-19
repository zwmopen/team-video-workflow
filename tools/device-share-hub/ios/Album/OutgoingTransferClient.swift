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
                let json = try JSONSerialization.data(withJSONObject: ["taskId": taskID, "text": "", "fileCount": items.count])
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
                DispatchQueue.main.async { progress(100, "已传送到“\(peer.name)”"); completion(.success(())) }
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
        semaphore.wait()
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
            self.progress?(percent, "正在传给“\(self.peerName)” \(percent)%")
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
