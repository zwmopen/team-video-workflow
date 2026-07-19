import Foundation
import UIKit

enum AlbumUpdateChecker {
    private static let endpoint = URL(string: "https://raw.githubusercontent.com/zwmopen/gallery-updates/refs/heads/main/latest.json")!

    static func check(from controller: UIViewController) {
        let waiting = UIAlertController(title: nil, message: "正在检查新版本…", preferredStyle: .alert)
        controller.present(waiting, animated: true)
        var request = URLRequest(url: endpoint)
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
                    let candidate = ((object["tag_name"] as? String) ?? "").replacingOccurrences(of: "v", with: "", options: [.anchored, .caseInsensitive])
                    let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
                    guard isNewer(candidate, than: current) else {
                        show(controller, title: "已是最新版本", message: "当前版本 (current)")
                        return
                    }
                    let page = (object["html_url"] as? String).flatMap(URL.init(string:))
                    let alert = UIAlertController(title: "发现新版本 (candidate)",
                                                  message: "会打开下载页面，由你确认安装；不会静默更新。",
                                                  preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "稍后", style: .cancel))
                    if let page = page {
                        alert.addAction(UIAlertAction(title: "查看更新", style: .default) { _ in
                            UIApplication.shared.open(page, options: [:], completionHandler: nil)
                        })
                    }
                    controller.present(alert, animated: true)
                }
            }
        }.resume()
    }

    private static func isNewer(_ candidate: String, than current: String) -> Bool {
        let left = candidate.split(separator: ".").map { Int($0.prefix { $0.isNumber }) ?? 0 }
        let right = current.split(separator: ".").map { Int($0.prefix { $0.isNumber }) ?? 0 }
        for index in 0..<max(left.count, right.count) {
            let a = index < left.count ? left[index] : 0
            let b = index < right.count ? right[index] : 0
            if a != b { return a > b }
        }
        return false
    }

    private static func show(_ controller: UIViewController, title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        controller.present(alert, animated: true)
    }
}
