import UIKit

enum LiveEngineSettings {
    static func show(menu: LiveQuickMenu, anchor: UIView, changed: @escaping () -> Void) {
        let values = LiveEnginePreference.allCases
        menu.show(anchor: anchor, labels: values.map { $0.title },
                  selected: values.firstIndex(of: LiveEnginePreference.preferred) ?? 0) { index in
            LiveEnginePreference.preferred = values[index]
            changed()
            UIAccessibility.post(notification: .announcement, argument: "偏好已保存；当前实际处理仍为本地，无自动上传")
        }
    }
}
