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

    // MARK: - DSH-181 主题感知品牌色（深色调色板对齐 Android ThemePalette）

    /// 浅色 #E2F4EC（沿用原值）；深色 #163826
    static var brandLightBg: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 22/255, green: 56/255, blue: 38/255, alpha: 1)
                    : UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
            }
        }
        return UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
    }

    /// 浅色 #0F8758（沿用原值）；深色 #4BD78C
    static var brandLightText: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 75/255, green: 215/255, blue: 140/255, alpha: 1)
                    : UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)
            }
        }
        return UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)
    }

    /// 在线蓝文字：浅色 rgb(2,132,199)；深色 rgb(86,190,240)
    static var onlineBlueText: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 86/255, green: 190/255, blue: 240/255, alpha: 1)
                    : UIColor(red: 2/255, green: 132/255, blue: 199/255, alpha: 1)
            }
        }
        return UIColor(red: 2/255, green: 132/255, blue: 199/255, alpha: 1)
    }

    /// 在线蓝底：浅色 rgb(224,242,254)；深色 rgb(11,54,80)
    static var onlineBlueBg: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 11/255, green: 54/255, blue: 80/255, alpha: 1)
                    : UIColor(red: 224/255, green: 242/255, blue: 254/255, alpha: 1)
            }
        }
        return UIColor(red: 224/255, green: 242/255, blue: 254/255, alpha: 1)
    }

    /// 状态警示（琥珀）：浅色文字 0.52/0.32/0.05、底 rgb(250,243,224)；深色文字 rgb(255,205,112)、底 rgb(66,48,13)
    static var statusWarningText: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 255/255, green: 205/255, blue: 112/255, alpha: 1)
                    : UIColor(red: 0.52, green: 0.32, blue: 0.05, alpha: 1)
            }
        }
        return UIColor(red: 0.52, green: 0.32, blue: 0.05, alpha: 1)
    }
    static var statusWarningBg: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 66/255, green: 48/255, blue: 13/255, alpha: 1)
                    : UIColor(red: 250/255, green: 243/255, blue: 224/255, alpha: 1)
            }
        }
        return UIColor(red: 250/255, green: 243/255, blue: 224/255, alpha: 1)
    }

    /// 本地模式状态（蓝）：浅色文字 0.15/0.35/0.55、底 rgb(235,243,250)；深色文字 rgb(120,180,250)、底 rgb(20,38,60)
    static var statusLocalText: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 120/255, green: 180/255, blue: 250/255, alpha: 1)
                    : UIColor(red: 0.15, green: 0.35, blue: 0.55, alpha: 1)
            }
        }
        return UIColor(red: 0.15, green: 0.35, blue: 0.55, alpha: 1)
    }
    static var statusLocalBg: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 20/255, green: 38/255, blue: 60/255, alpha: 1)
                    : UIColor(red: 235/255, green: 243/255, blue: 250/255, alpha: 1)
            }
        }
        return UIColor(red: 235/255, green: 243/255, blue: 250/255, alpha: 1)
    }

    /// 列表视图紫：浅色文字 rgb(124,58,237)、底 rgb(237,233,254)；深色文字 rgb(196,160,255)、底 rgb(52,32,92)
    static var listPurpleText: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 196/255, green: 160/255, blue: 255/255, alpha: 1)
                    : UIColor(red: 124/255, green: 58/255, blue: 237/255, alpha: 1)
            }
        }
        return UIColor(red: 124/255, green: 58/255, blue: 237/255, alpha: 1)
    }
    static var listPurpleBg: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 52/255, green: 32/255, blue: 92/255, alpha: 1)
                    : UIColor(red: 237/255, green: 233/255, blue: 254/255, alpha: 1)
            }
        }
        return UIColor(red: 237/255, green: 233/255, blue: 254/255, alpha: 1)
    }

    /// 中性胶囊（排序未选中）：浅色底 rgb(240,242,241)、文字 rgb(60,65,63)；深色底 rgb(44,50,47)、文字 rgb(200,208,203)
    static var neutralChipBg: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 44/255, green: 50/255, blue: 47/255, alpha: 1)
                    : UIColor(red: 240/255, green: 242/255, blue: 241/255, alpha: 1)
            }
        }
        return UIColor(red: 240/255, green: 242/255, blue: 241/255, alpha: 1)
    }
    static var neutralChipText: UIColor {
        if #available(iOS 13.0, *) {
            return UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 200/255, green: 208/255, blue: 203/255, alpha: 1)
                    : UIColor(red: 60/255, green: 65/255, blue: 63/255, alpha: 1)
            }
        }
        return UIColor(red: 60/255, green: 65/255, blue: 63/255, alpha: 1)
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
