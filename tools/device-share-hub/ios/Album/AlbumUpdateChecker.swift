import Foundation
import UIKit

enum AlbumUpdateChecker {
    private static let stableEndpoint = URL(string: "https://raw.githubusercontent.com/zwmopen/gallery-updates/refs/heads/main/latest.json")!
    private static let stableAltStoreSource = "https://raw.githubusercontent.com/zwmopen/gallery-updates/main/altstore.json"
    private static let altStoreURL = URL(string: "altstore-classic://")!

    static func check(from controller: UIViewController) {
        let waiting = UIAlertController(title: nil, message: "正在检查新版本…", preferredStyle: .alert)
        controller.present(waiting, animated: true)
        var request = URLRequest(url: stableEndpoint)
        request.timeoutInterval = 8
        request.setValue("zwm-album-ios", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { data, response, error in
            DispatchQueue.main.async {
                waiting.dismiss(animated: true) {
                    guard error == nil, let http = response as? HTTPURLResponse, http.statusCode == 200,
                          let data = data,
                          let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                        show(controller, title: "检查失败", message: "暂时无法连接更新服务，请确认网络后再试。")
                        return
                    }
                    let iosBlock = object["ios"] as? [String: Any]
                    let raw = (iosBlock?["version_name"] as? String)
                        ?? (object["version_name"] as? String)
                        ?? (object["tag_name"] as? String) ?? ""
                    let candidate = raw.replacingOccurrences(of: "v", with: "", options: [.anchored, .caseInsensitive])
                    let candidateBuild = (iosBlock?["build"] as? NSNumber)?.intValue ?? 0
                    let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
                    let currentBuild = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
                    guard isNewer(candidate: candidate, build: candidateBuild, than: current, currentBuild: currentBuild) else {
                        show(controller, title: "已经是最新版本", message: "当前版本 \(current)（build \(currentBuild)）")
                        return
                    }
                    showUpdate(controller, candidate: candidate, candidateBuild: candidateBuild,
                               current: current, currentBuild: currentBuild,
                               source: stableAltStoreSource)
                }
            }
        }.resume()
    }

    private static func showUpdate(_ controller: UIViewController, candidate: String, candidateBuild: Int,
                                   current: String, currentBuild: Int,
                                   source: String) {
        let alert = UIAlertController(
            title: "发现新版本 \(candidate)（build \(candidateBuild)）",
            message: "当前版本 \(current)（build \(currentBuild)）。新版本已进入 AltStore 更新源，请在 AltStore 的 My Apps 中点“更新”。这里不会删除作品文件。",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "打开 AltStore", style: .default) { _ in
            UIApplication.shared.open(altStoreURL)
        })
        alert.addAction(UIAlertAction(title: "复制更新源", style: .default) { _ in
            UIPasteboard.general.string = source
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                show(controller, title: "更新源已复制", message: "打开 AltStore → Sources → + 粘贴即可。")
            }
        })
        alert.addAction(UIAlertAction(title: "稍后", style: .cancel))
        controller.present(alert, animated: true)
    }

    private static func isNewer(candidate: String, build: Int, than current: String, currentBuild: Int) -> Bool {
        let left = candidate.split(separator: ".").map { Int($0.prefix { $0.isNumber }) ?? 0 }
        let right = current.split(separator: ".").map { Int($0.prefix { $0.isNumber }) ?? 0 }
        for index in 0..<max(left.count, right.count) {
            let a = index < left.count ? left[index] : 0
            let b = index < right.count ? right[index] : 0
            if a != b { return a > b }
        }
        return build > currentBuild
    }

    private static func show(_ controller: UIViewController, title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        controller.present(alert, animated: true)
    }
}
