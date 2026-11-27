import CoreGraphics
import Foundation

/// Dynamic video quality tiers for Live Photo encoding.
/// Static JPEG/HEIC covers always use lossless metadata injection regardless of video tier.
enum LiveQualityTier: String, CaseIterable {
    case smooth = "smooth"
    case hd = "hd"
    case original = "original"

    var title: String {
        switch self {
        case .smooth: return "流畅模式"
        case .hd: return "高清模式（推荐）"
        case .original: return "原画模式"
        }
    }

    var shortLabel: String {
        switch self {
        case .smooth: return "流畅 1080P"
        case .hd: return "高清 1440P"
        case .original: return "原画 2048P"
        }
    }

    var summary: String {
        switch self {
        case .smooth: return "动态视频 1080P · 2-4Mbps · 省电快速"
        case .hd: return "动态视频 1440P · 6-10Mbps · 日常发布默认"
        case .original: return "动态视频 2048P · 12-20Mbps · 精品细节"
        }
    }

    var targetLongSide: Int {
        switch self {
        case .smooth: return 1080
        case .hd: return 1440
        case .original: return 2048
        }
    }

    var targetBitrateBps: Int {
        switch self {
        case .smooth: return 3_200_000
        case .hd: return 8_000_000
        case .original: return 16_000_000
        }
    }

    var minBitrateBps: Int {
        switch self {
        case .smooth: return 2_000_000
        case .hd: return 6_000_000
        case .original: return 12_000_000
        }
    }

    var maxBitrateBps: Int {
        switch self {
        case .smooth: return 4_000_000
        case .hd: return 10_000_000
        case .original: return 20_000_000
        }
    }

    var nextLower: LiveQualityTier? {
        switch self {
        case .original: return .hd
        case .hd: return .smooth
        case .smooth: return nil
        }
    }

    func computeEncodedSize(srcW: Int, srcH: Int) -> (width: Int, height: Int) {
        let safeW = max(2, srcW)
        let safeH = max(2, srcH)
        let longSide = max(safeW, safeH)
        let scale: Double = longSide > targetLongSide ? (Double(targetLongSide) / Double(longSide)) : 1.0
        let rawW = Int((Double(safeW) * scale).rounded())
        let rawH = Int((Double(safeH) * scale).rounded())
        let outW = max(32, (min(safeW, rawW) / 16) * 16)
        let outH = max(32, (min(safeH, rawH) / 16) * 16)
        return (outW > 0 ? outW : 32, outH > 0 ? outH : 32)
    }

    func computeBitrateBps(width: Int, height: Int) -> Int {
        let pixels = Double(max(1, width * height))
        let refPixels = Double(targetLongSide) * max(1.0, (Double(targetLongSide) * 0.75).rounded())
        let ratio = max(0.25, min(1.5, pixels / refPixels))
        let scaled = Int((Double(targetBitrateBps) * ratio).rounded())
        return max(minBitrateBps, min(maxBitrateBps, scaled))
    }
}
