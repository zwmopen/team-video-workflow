import CoreGraphics
import Foundation

enum AutoLiveMotionMode: String, CaseIterable {
    case naturalHold = "natural_hold"
    case pushDrift = "handheld_push_drift_v1"
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
        case .pushDrift: return "自然推近·轻晃侧移"
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
        case .pushDrift: return "前快后慢推近，后段轻微侧移，参考实况样本曲线"
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

    static var defaultRatio: Double { AutoLivePreferences.defaultRatio }
    static var defaultDuration: Double { AutoLivePreferences.defaultDuration }
    static var defaultFPS: Int { AutoLivePreferences.defaultFPS }

    static func clampRatio(_ r: Double) -> Double {
        AutoLivePreferences.clampRatio(r)
    }

    static func clampDuration(_ d: Double) -> Double {
        AutoLivePreferences.clampDuration(d)
    }

    private static let pushZ: [Double] = [0.000000, 0.005147, 0.010059, 0.015035, 0.020014, 0.024423, 0.029570, 0.034068, 0.038547, 0.043122,
        0.047424, 0.051740, 0.055742, 0.059774, 0.063531, 0.067648, 0.071081, 0.074225, 0.077471, 0.080498,
        0.083755, 0.086177, 0.088734, 0.090810, 0.092911, 0.094454, 0.096326, 0.097324, 0.098396, 0.098922,
        0.099791, 0.099937, 0.100696, 0.101306, 0.102127, 0.103062, 0.103789, 0.104631, 0.105781, 0.106773,
        0.106803, 0.107560, 0.108322, 0.109408, 0.110110, 0.110823, 0.111166, 0.111724, 0.112540, 0.112768,
        0.113310, 0.113580, 0.114407, 0.114073, 0.114779, 0.114588, 0.114858, 0.115460, 0.115503, 0.115388]
    private static let pushX: [Double] = [0.000000, 0.001051, 0.000706, 0.000370, 0.001428, 0.000721, 0.001817, 0.001116, 0.001828, 0.001107,
        0.000033, 0.000758, -0.000358, 0.000027, 0.000410, 0.000783, 0.002294, 0.002302, 0.002298, 0.000778,
        0.000778, 0.000372, 0.000007, -0.000736, 0.000401, 0.001168, 0.001882, 0.002320, 0.002744, 0.001626,
        0.000460, -0.001149, -0.000786, 0.001178, 0.000026, -0.001123, -0.003840, -0.005091, -0.006197, -0.007365,
        -0.008945, -0.010121, -0.009741, -0.010887, -0.010531, -0.011712, -0.014800, -0.015999, -0.017265, -0.020384,
        -0.021461, -0.023100, -0.022755, -0.024382, -0.024274, -0.025846, -0.025574, -0.027083, -0.028628, -0.031778]
    private static let pushY: [Double] = [0.000000, 0.000512, 0.000790, 0.001052, 0.000225, 0.000248, 0.000548, 0.000537, 0.000528, 0.001633,
        0.001690, 0.001402, 0.001110, 0.000848, 0.000290, 0.000021, 0.000586, -0.000272, 0.000008, 0.001440,
        0.001696, 0.001703, 0.001715, 0.001769, 0.001436, 0.000857, 0.000588, 0.000006, -0.000558, -0.000250,
        0.000039, 0.000017, 0.001445, 0.001751, 0.002010, 0.001470, 0.001720, 0.000834, 0.000278, 0.000525,
        -0.000278, -0.001148, -0.000874, -0.000277, 0.000015, 0.001469, 0.001777, 0.001759, 0.002020, 0.002399,
        0.001468, 0.000277, 0.000601, -0.000580, -0.000326, -0.000255, -0.000317, -0.000006, 0.001204, 0.001151]
    private static func sample(_ curve: [Double], _ p: Double) -> Double {
        let index = p * Double(curve.count - 1)
        let lo = Int(index)
        return curve[lo] + (curve[min(lo + 1, curve.count - 1)] - curve[lo]) * (index - Double(lo))
    }

    func computeFrame(ratio: Double, frameIndex: Int, totalFrames: Int) -> (zoom: Double, offsetX: Double, offsetY: Double) {
        let clampedRatio = min(self == .pushDrift ? 1.12 : 1.08, AutoLivePreferences.clampRatio(ratio))
        let den = max(1, totalFrames - 1)
        let p = max(0.0, min(1.0, Double(frameIndex) / Double(den)))
        let ease = (1.0 - cos(Double.pi * p)) / 2.0

        switch self {
        case .pushDrift:
            let gain = (clampedRatio - 1.0) / 0.115503
            let delta = Self.sample(Self.pushZ, p) * gain
            guard delta > 1e-9 else { return (1.0, 0.5, 0.5) }
            let ox = min(1.0, max(0.0, 0.5 - Self.sample(Self.pushX, p) * gain / delta))
            let oy = min(1.0, max(0.0, 0.5 - Self.sample(Self.pushY, p) * gain / delta))
            return (1.0 + delta, ox, oy)
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

typealias MotionPreset = AutoLiveMotionMode

enum AutoLivePreferences {
    private static let autoLiveKey = "album.autoLiveOnShareEnabled.v1"
    private static let autoLiveModeKey = "album.autoLiveMotionMode.v1"
    private static let autoLiveRatioKey = "album.autoLiveRatio.v1"
    private static let autoLiveDurationKey = "album.autoLiveDuration.v1"
    private static let autoLiveSkipExistingKey = "album.autoLiveSkipExisting.v1"
    private static let autoLiveQualityTierKey = "album.autoLiveQualityTier.v1"

    static let defaultRatio: Double = 1.018
    static let defaultDuration: Double = 1.6
    static let defaultFPS: Int = 30

    static func clampRatio(_ r: Double) -> Double {
        return max(1.005, min(1.12, r))
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
            let previous = motionMode
            UserDefaults.standard.set(newValue.rawValue, forKey: autoLiveModeKey)
            if previous != newValue {
                if newValue == .pushDrift { motionRatio = 1.116; motionDuration = 2.0 }
                else if previous == .pushDrift { motionRatio = defaultRatio; motionDuration = defaultDuration }
            }
        }
    }

    static var qualityTier: LiveQualityTier {
        get {
            if let raw = UserDefaults.standard.string(forKey: autoLiveQualityTierKey),
               let parsed = LiveQualityTier(rawValue: raw) {
                return parsed
            }
            return .hd
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: autoLiveQualityTierKey)
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
