import CoreGraphics
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

    func computeFrame(ratio: Double, frameIndex: Int, totalFrames: Int) -> (zoom: Double, offsetX: Double, offsetY: Double) {
        let clampedRatio = AutoLivePreferences.clampRatio(ratio)
        let den = max(1, totalFrames - 1)
        let p = max(0.0, min(1.0, Double(frameIndex) / Double(den)))
        let ease = (1.0 - cos(Double.pi * p)) / 2.0

        switch self {
        case .naturalHold:
            let delta = max(0.008, clampedRatio - 1.0)
            let z = 1.0 + delta * (0.15 + 0.85 * ease)
            let ox = 0.5 + 0.14 * sin(Double.pi * p)
            let oy = 0.5 + 0.10 * sin(Double.pi * p)
            return (z, ox, oy)
        case .subtleStill:
            let delta = max(0.005, (clampedRatio - 1.0) * 0.45)
            return (1.0 + delta * ease, 0.5, 0.5)
        case .zoomOut:
            let z = clampedRatio - (clampedRatio - 1.0) * ease
            return (z, 0.5, 0.5)
        case .panUp:
            return (clampedRatio, 0.5, 1.0 - ease)
        case .panDown:
            return (clampedRatio, 0.5, ease)
        case .panLeft:
            return (clampedRatio, 1.0 - ease, 0.5)
        case .panRight:
            return (clampedRatio, ease, 0.5)
        case .handheld:
            let env = sin(Double.pi * p)
            let z = 1.0 + (clampedRatio - 1.0) * (0.45 + 0.35 * env)
            let ox = 0.5 + 0.20 * sin(2.0 * Double.pi * p) * env
            let oy = 0.5 + 0.14 * sin(1.5 * Double.pi * p) * env
            return (z, ox, oy)
        case .zoomIn:
            let z = 1.0 + (clampedRatio - 1.0) * ease
            return (z, 0.5, 0.5)
        }
    }
}

enum AutoLivePreferences {
    private static let autoLiveKey = "album.autoLiveOnShareEnabled.v1"
    private static let autoLiveModeKey = "album.autoLiveMotionMode.v1"
    private static let autoLiveRatioKey = "album.autoLiveRatio.v1"
    private static let autoLiveDurationKey = "album.autoLiveDuration.v1"
    private static let autoLiveSkipExistingKey = "album.autoLiveSkipExisting.v1"

    static let defaultRatio: Double = 1.018
    static let defaultDuration: Double = 1.6
    static let defaultFPS: Int = 30

    static func clampRatio(_ r: Double) -> Double {
        return max(1.005, min(1.08, r))
    }

    static func clampDuration(_ d: Double) -> Double {
        return max(1.0, min(3.0, d))
    }

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

    static var motionRatio: Double {
        get {
            let v = UserDefaults.standard.double(forKey: autoLiveRatioKey)
            return v > 0 ? clampRatio(v) : defaultRatio
        }
        set {
            UserDefaults.standard.set(clampRatio(newValue), forKey: autoLiveRatioKey)
        }
    }

    static var motionDuration: Double {
        get {
            let v = UserDefaults.standard.double(forKey: autoLiveDurationKey)
            return v > 0 ? clampDuration(v) : defaultDuration
        }
        set {
            UserDefaults.standard.set(clampDuration(newValue), forKey: autoLiveDurationKey)
        }
    }

    static var skipExistingLive: Bool {
        get {
            if UserDefaults.standard.object(forKey: autoLiveSkipExistingKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: autoLiveSkipExistingKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: autoLiveSkipExistingKey)
        }
    }
}
