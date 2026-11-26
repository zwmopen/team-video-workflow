import CoreGraphics
import Foundation

enum MotionPreset: String, CaseIterable {
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

    static let defaultRatio: Double = 1.018
    static let defaultDuration: Double = 1.6
    static let defaultFPS: Int = 30

    static func clampRatio(_ value: Double) -> Double {
        return max(1.005, min(1.08, value))
    }

    static func clampDuration(_ value: Double) -> Double {
        return max(1.0, min(3.0, value))
    }

    func cropRect(imageSize: CGSize, ratio rawRatio: Double, frameIndex: Int, totalFrames: Int) -> CGRect {
        let ratio = MotionPreset.clampRatio(rawRatio)
        let den = max(1, totalFrames - 1)
        let p = max(0.0, min(1.0, Double(frameIndex) / Double(den)))
        let ease = (1.0 - cos(.pi * p)) / 2.0

        var zoom = ratio
        var ox = 0.5
        var oy = 0.5

        switch self {
        case .naturalHold:
            let delta = max(0.008, ratio - 1.0)
            zoom = 1.0 + delta * (0.15 + 0.85 * ease)
            ox = 0.5 + 0.14 * sin(.pi * p)
            oy = 0.5 + 0.10 * sin(.pi * p)
        case .subtleStill:
            let delta = max(0.005, (ratio - 1.0) * 0.45)
            zoom = 1.0 + delta * ease
        case .zoomIn:
            zoom = 1.0 + (ratio - 1.0) * ease
        case .zoomOut:
            zoom = ratio - (ratio - 1.0) * ease
        case .panUp:
            oy = 1.0 - ease
        case .panDown:
            oy = ease
        case .panLeft:
            ox = 1.0 - ease
        case .panRight:
            ox = ease
        case .handheld:
            let env = sin(.pi * p)
            zoom = 1.0 + (ratio - 1.0) * (0.45 + 0.35 * env)
            ox = 0.5 + 0.20 * sin(2.0 * .pi * p) * env
            oy = 0.5 + 0.14 * sin(1.5 * .pi * p) * env
        }

        zoom = max(1.0, zoom)
        ox = max(0.0, min(1.0, ox))
        oy = max(0.0, min(1.0, oy))

        let w = imageSize.width / CGFloat(zoom)
        let h = imageSize.height / CGFloat(zoom)
        let x = (imageSize.width - w) * CGFloat(ox)
        let y = (imageSize.height - h) * CGFloat(oy)
        return CGRect(x: x, y: y, width: w, height: h)
    }
}
