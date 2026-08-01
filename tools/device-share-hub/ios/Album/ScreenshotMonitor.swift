import Foundation
import Photos
import UIKit

final class ScreenshotMonitor {
    private static let detectionKey = "album.screenshotDetectionEnabled"
    private static let autoSendKey = "album.screenshotAutoSendEnabled"
    private static let targetIDKey = "album.screenshotMainDeviceId"
    private static let targetNameKey = "album.screenshotMainDeviceName"
    private static let checkedAtKey = "album.screenshotCheckedAt"
    private static var isChecking = false
    private static var sender: OutgoingTransferClient?

    static var detectionEnabled: Bool {
        return UserDefaults.standard.bool(forKey: detectionKey)
    }

    static var autoSendEnabled: Bool {
        get { return UserDefaults.standard.object(forKey: autoSendKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: autoSendKey) }
    }

    static var mainDeviceName: String {
        return UserDefaults.standard.string(forKey: targetNameKey) ?? "未设置"
    }

    static func setMainDevice(_ peer: TransferPeer?) {
        if let peer = peer {
            UserDefaults.standard.set(peer.id, forKey: targetIDKey)
            UserDefaults.standard.set(peer.name.isEmpty ? peer.model : peer.name, forKey: targetNameKey)
        } else {
            UserDefaults.standard.removeObject(forKey: targetIDKey)
            UserDefaults.standard.removeObject(forKey: targetNameKey)
        }
    }

    static func setDetectionEnabled(_ enabled: Bool, completion: @escaping (Bool) -> Void) {
        guard enabled else {
            UserDefaults.standard.set(false, forKey: detectionKey)
            completion(true)
            return
        }
        let finish: (PHAuthorizationStatus) -> Void = { status in
            let granted: Bool
            if #available(iOS 14.0, *) { granted = status == .authorized || status == .limited }
            else { granted = status == .authorized }
            if granted {
                UserDefaults.standard.set(true, forKey: detectionKey)
                UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: checkedAtKey)
            }
            DispatchQueue.main.async { completion(granted) }
        }
        let current = PHPhotoLibrary.authorizationStatus()
        if current == .notDetermined { PHPhotoLibrary.requestAuthorization(finish) }
        else { finish(current) }
    }

    static func check(from presenter: UIViewController?) {
        guard detectionEnabled, !isChecking,
              UIApplication.shared.applicationState == .active else { return }
        let status = PHPhotoLibrary.authorizationStatus()
        let granted: Bool
        if #available(iOS 14.0, *) { granted = status == .authorized || status == .limited }
        else { granted = status == .authorized }
        guard granted else { return }

        let now = Date()
        let previous = UserDefaults.standard.double(forKey: checkedAtKey)
        UserDefaults.standard.set(now.timeIntervalSince1970, forKey: checkedAtKey)
        guard previous > 0 else { return }
        isChecking = true

        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "mediaType == %d AND creationDate > %@",
                                        PHAssetMediaType.image.rawValue,
                                        Date(timeIntervalSince1970: previous) as NSDate)
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 8
        let assets = PHAsset.fetchAssets(with: options)
        var screenshot: PHAsset?
        assets.enumerateObjects { asset, _, stop in
            if asset.mediaSubtypes.contains(.photoScreenshot) {
                screenshot = asset
                stop.pointee = true
            }
        }
        guard let asset = screenshot else { isChecking = false; return }
        export(asset) { result in
            DispatchQueue.main.async {
                isChecking = false
                switch result {
                case .success(let item): route(item, from: presenter)
                case .failure: break
                }
            }
        }
    }

    private static func export(_ asset: PHAsset, completion: @escaping (Result<OutgoingItem, Error>) -> Void) {
        let resource = PHAssetResource.assetResources(for: asset).first
        let originalName = resource?.originalFilename ?? "screenshot-\(Int(Date().timeIntervalSince1970)).png"
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("screenshot-\(UUID().uuidString)-\(originalName)")
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        guard let resource = resource else {
            completion(.failure(ScreenshotError.unavailable)); return
        }
        PHAssetResourceManager.default().writeData(for: resource, toFile: destination, options: options) { error in
            if let error = error { completion(.failure(error)); return }
            completion(.success(OutgoingItem(url: destination, name: originalName,
                                             mime: "image/png", temporary: true)))
        }
    }

    private static func route(_ item: OutgoingItem, from presenter: UIViewController?) {
        let peers = PeerDirectory.shared.peers()
        let targetID = UserDefaults.standard.string(forKey: targetIDKey)
        let target = targetID.flatMap { id in peers.first { $0.id == id } }
        if autoSendEnabled, let target = target {
            send(item, to: target, presenter: presenter)
            return
        }
        guard let presenter = presenter, presenter.presentedViewController == nil else {
            try? FileManager.default.removeItem(at: item.url); return
        }
        let alert = UIAlertController(title: "发现新截图",
                                      message: peers.isEmpty ? "当前没有在线设备。截图已保留在系统相册中。" : "要发送到哪台设备？",
                                      preferredStyle: .actionSheet)
        peers.forEach { peer in
            let name = peer.name.isEmpty ? peer.model : peer.name
            alert.addAction(UIAlertAction(title: "发送到 \(name)", style: .default) { _ in
                send(item, to: peer, presenter: presenter)
            })
        }
        alert.addAction(UIAlertAction(title: "暂不发送", style: .cancel) { _ in
            try? FileManager.default.removeItem(at: item.url)
        })
        if let popover = alert.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(x: presenter.view.bounds.midX,
                                        y: presenter.view.bounds.midY, width: 1, height: 1)
        }
        presenter.present(alert, animated: true)
    }

    private static func send(_ item: OutgoingItem, to peer: TransferPeer, presenter: UIViewController?) {
        let client = OutgoingTransferClient()
        sender = client
        let relay = RelayTaskInfo(object: [
            "messageId": "ios-shot-\(UUID().uuidString.lowercased())",
            "originId": DeviceIdentity.id,
            "destinationId": peer.id,
            "previousHopId": DeviceIdentity.id,
            "contentKind": "screenshot",
            "expiresAt": Int64((Date().timeIntervalSince1970 + 3600) * 1000),
            "hopLimit": 4
        ])
        client.send([item], to: peer, progress: { _, _ in }, relay: relay) { result in
            sender = nil
            guard case .failure(let error) = result, let presenter = presenter,
                  presenter.presentedViewController == nil else { return }
            let alert = UIAlertController(title: "截图发送失败",
                                          message: error.localizedDescription,
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "知道了", style: .default))
            presenter.present(alert, animated: true)
        }
    }
}

private enum ScreenshotError: Error { case unavailable }
