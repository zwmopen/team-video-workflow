import Foundation

enum AutoLiveMotionMode: String, CaseIterable {
    case naturalHold = "natural_hold"
    case subtleStill = "subtle_still"
    case zoomIn = "zoom_in"
    case zoomOut = "zoom_out"
    case panUp = "pan_up"
    case panDown = "pan_down"
    case panLeft = "pan_left"
    case panRight = "pan_right"
    case handheld = "handheld"

    var title: String {
        switch self {
        case .naturalHold: return "自然手持微动（推荐）"
        case .subtleStill: return "几乎静止（极微呼吸）"
        case .zoomIn: return "轻柔推近"
        case .zoomOut: return "轻柔拉远"
        case .panUp: return "微幅上移"
        case .panDown: return "微幅下移"
        case .panLeft: return "微幅左移"
        case .panRight: return "微幅右移"
        case .handheld: return "温和手持轻晃"
        }
    }

    var summary: String {
        switch self {
        case .naturalHold: return "模拟真实持机拍摄的轻微呼吸稳态，最接近真机实拍"
        case .subtleStill: return "极小幅度变化，几乎不改构图，仅保留实况标识"
        case .zoomIn: return "画面极轻微平滑向前推近（缓入缓出）"
        case .zoomOut: return "画面极轻微平滑向后舒展（缓入缓出）"
        case .panUp: return "画面极轻微平滑向上移动"
        case .panDown: return "画面极轻微平滑向下移动"
        case .panLeft: return "画面极轻微平滑向左移动"
        case .panRight: return "画面极轻微平滑向右移动"
        case .handheld: return "克制的小幅自然手持微晃"
        }
    }
}

enum AutoLivePreferences {
    private static let autoLiveKey = "album.autoLiveOnShareEnabled.v1"
    private static let autoLiveModeKey = "album.autoLiveMotionMode.v1"

    static var isEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: autoLiveKey) == nil {
                return false
            }
            return UserDefaults.standard.bool(forKey: autoLiveKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: autoLiveKey)
        }
    }

    static var motionMode: AutoLiveMotionMode {
        get {
            if let raw = UserDefaults.standard.string(forKey: autoLiveModeKey),
               let parsed = AutoLiveMotionMode(rawValue: raw) {
                return parsed
            }
            return .naturalHold
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: autoLiveModeKey)
        }
    }
}
