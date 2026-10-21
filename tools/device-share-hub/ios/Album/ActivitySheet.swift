import UIKit

enum AppColors {
    static var groupedTableStyle: UITableView.Style {
        if #available(iOS 13.0, *) { return .insetGrouped }
        return .grouped
    }
    static var background: UIColor {
        if #available(iOS 13.0, *) { return .systemBackground }
        return .white
    }
    static var secondaryBackground: UIColor {
        if #available(iOS 13.0, *) { return .secondarySystemBackground }
        return UIColor(white: 0.96, alpha: 1)
    }
    static var sharedBackground: UIColor {
        if #available(iOS 13.0, *) { return .secondarySystemFill }
        return UIColor(white: 0.91, alpha: 1)
    }
    static var text: UIColor {
        if #available(iOS 13.0, *) { return .label }
        return .black
    }
    static var secondaryText: UIColor {
        if #available(iOS 13.0, *) { return .secondaryLabel }
        return UIColor(white: 0.42, alpha: 1)
    }
    static var separator: UIColor {
        if #available(iOS 13.0, *) { return .separator }
        return UIColor(white: 0.78, alpha: 1)
    }
}

// MARK: - 主题外观模式（跟随系统、浅色模式、深色模式）
enum AppThemeMode: Int {
    case system = 0
    case light = 1
    case dark = 2

    var title: String {
        switch self {
        case .system: return "跟随系统"
        case .light: return "浅色模式"
        case .dark: return "深色模式"
        }
    }
}

enum AppThemePreferences {
    private static let key = "appThemeMode"

    static var currentMode: AppThemeMode {
        get {
            let val = UserDefaults.standard.integer(forKey: key)
            return AppThemeMode(rawValue: val) ?? .system
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: key)
            applyTheme()
        }
    }

    static func applyTheme(to targetWindow: UIWindow? = nil) {
        if #available(iOS 13.0, *) {
            let style: UIUserInterfaceStyle
            switch currentMode {
            case .system: style = .unspecified
            case .light: style = .light
            case .dark: style = .dark
            }
            if let targetWindow = targetWindow {
                targetWindow.overrideUserInterfaceStyle = style
            } else {
                UIApplication.shared.windows.forEach { $0.overrideUserInterfaceStyle = style }
            }
        }
    }
}
