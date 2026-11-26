import AVFoundation
import CoreGraphics
import ImageIO
import MobileCoreServices
import Photos
import UIKit

enum LivePhotoEngineError: LocalizedError {
    case invalidImage
    case writerFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidImage: return "无法读取或解码所选图片"
        case .writerFailed(let msg): return "生成实况失败：\(msg)"
        }
    }
}

struct LivePhotoPair {
    let imageURL: URL
    let videoURL: URL
}

/// Primary on-device hardware AVAssetWriter live-photo generator (1440px, sub-pixel CGContext transform).
/// Compatible with iOS 12.0+ deployment target.
final class OnDeviceLivePhotoEngine {
    static let shared = OnDeviceLivePhotoEngine()
    private init() {}

    func convertToLivePair(
        image: UIImage,
        preset: AutoLiveMotionMode,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LivePhotoPair {
        let upright = normalizeUpright(image)
        guard let cgImage = upright.cgImage else {
            throw LivePhotoEngineError.invalidImage
        }
        let assetId = UUID().uuidString
        let stageDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("album-live-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: stageDir, withIntermediateDirectories: true)

        let jpgURL = stageDir.appendingPathComponent("live_cover.jpg")
        let movURL = stageDir.appendingPathComponent("live_motion.mov")

        try writeAppleLiveJPEG(cgImage: cgImage, assetId: assetId, destination: jpgURL)
        try writeMotionMOV(
            cgImage: cgImage,
            preset: preset,
            ratio: ratio,
            duration: duration,
            assetId: assetId,
            destination: movURL
        )
        return LivePhotoPair(imageURL: jpgURL, videoURL: movURL)
    }

    func convertFileToLivePair(
        imageURL: URL,
        preset: AutoLiveMotionMode,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LivePhotoPair {
        guard let data = try? Data(contentsOf: imageURL),
              let image = UIImage(data: data) else {
            throw LivePhotoEngineError.invalidImage
        }
        return try convertToLivePair(image: image, preset: preset, ratio: ratio, duration: duration)
    }

    func convertAndSaveToPhotos(
        image: UIImage,
        preset: AutoLiveMotionMode,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LivePhotoPair {
        let pair = try convertToLivePair(image: image, preset: preset, ratio: ratio, duration: duration)
        try savePairToPhotos(pair)
        return pair
    }

    func savePairsToPhotos(_ pairs: [LivePhotoPair]) throws {
        guard !pairs.isEmpty else { return }
        var saveError: Error?
        let sem = DispatchSemaphore(value: 0)
        PHPhotoLibrary.shared().performChanges({
            for pair in pairs {
                let req = PHAssetCreationRequest.forAsset()
                let imgOpts = PHAssetResourceCreationOptions()
                imgOpts.shouldMoveFile = false
                let vidOpts = PHAssetResourceCreationOptions()
                vidOpts.shouldMoveFile = false
                req.addResource(with: .photo, fileURL: pair.imageURL, options: imgOpts)
                req.addResource(with: .pairedVideo, fileURL: pair.videoURL, options: vidOpts)
            }
        }, completionHandler: { success, error in
            if !success {
                saveError = error ?? LivePhotoEngineError.writerFailed("写入系统相册失败")
            }
            sem.signal()
        })
        sem.wait()
        if let err = saveError {
            throw err
        }
    }

    private func savePairToPhotos(_ pair: LivePhotoPair) throws {
        try savePairsToPhotos([pair])
    }

    private func normalizeUpright(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: image.size, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    private func writeAppleLiveJPEG(cgImage: CGImage, assetId: String, destination: URL) throws {
        guard let dest = CGImageDestinationCreateWithURL(
            destination as CFURL,
            kUTTypeJPEG,
            1,
            nil
        ) else {
            throw LivePhotoEngineError.writerFailed("无法创建 JPEG 输出")
        }
        let makerApple: [String: Any] = ["17": assetId]
        let props: [String: Any] = [
            kCGImageDestinationLossyCompressionQuality as String: 0.94,
            kCGImagePropertyMakerAppleDictionary as String: makerApple
        ]
        CGImageDestinationAddImage(dest, cgImage, props as CFDictionary)
        if !CGImageDestinationFinalize(dest) {
            throw LivePhotoEngineError.writerFailed("写入 Live JPEG 元数据失败")
        }
    }

    private func writeMotionMOV(
        cgImage: CGImage,
        preset: AutoLiveMotionMode,
        ratio: Double,
        duration: Double,
        assetId: String,
        destination: URL
    ) throws {
        let srcW = cgImage.width
        let srcH = cgImage.height
        let longSide = max(srcW, srcH)
        let shortSide = min(srcW, srcH)
        var scale = 1.0
        if longSide > 1440 {
            scale = 1440.0 / Double(longSide)
        } else if shortSide > 0 && shortSide < 320 {
            scale = 320.0 / Double(shortSide)
        }
        let outW = max(32, Int((Double(srcW) * scale / 16.0).rounded()) * 16)
        let outH = max(32, Int((Double(srcH) * scale / 16.0).rounded()) * 16)

        let writer = try AVAssetWriter(outputURL: destination, fileType: .mov)
        let contentItem = AVMutableMetadataItem()
        contentItem.key = "com.apple.quicktime.content.identifier" as NSString
        contentItem.keySpace = .quickTimeMetadata
        contentItem.value = assetId as NSString
        contentItem.dataType = kCMMetadataBaseDataType_UTF8 as String
        writer.metadata = [contentItem]

        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: outW,
            AVVideoHeightKey: outH,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: max(2_200_000, min(8_000_000, outW * outH * 4))
            ]
        ]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        input.expectsMediaDataInRealTime = false

        let pbAttribs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: outW,
            kCVPixelBufferHeightKey as String: outH
        ]
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: pbAttribs
        )

        guard writer.canAdd(input) else {
            throw LivePhotoEngineError.writerFailed("视频编码通道不可用")
        }
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        let fps = AutoLivePreferences.defaultFPS
        let totalFrames = max(12, Int((AutoLivePreferences.clampDuration(duration) * Double(fps)).rounded()))
        let cs = CGColorSpaceCreateDeviceRGB()

        for frame in 0..<totalFrames {
            while !input.isReadyForMoreMediaData {
                Thread.sleep(forTimeInterval: 0.005)
            }
            guard let pool = adaptor.pixelBufferPool else { continue }
            var pbOut: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pbOut)
            guard let pb = pbOut else { continue }

            CVPixelBufferLockBaseAddress(pb, [])
            if let base = CVPixelBufferGetBaseAddress(pb),
               let ctx = CGContext(
                data: base,
                width: outW,
                height: outH,
                bitsPerComponent: 8,
                bytesPerRow: CVPixelBufferGetBytesPerRow(pb),
                space: cs,
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
               ) {
                ctx.interpolationQuality = .high
                // Sub-pixel floating-point transform avoids 1px jitter from integer CGImage.cropping rects.
                let tf = preset.computeFrame(ratio: ratio, frameIndex: frame, totalFrames: totalFrames)
                let zoomedW = Double(outW) * tf.zoom
                let zoomedH = Double(outH) * tf.zoom
                let drawX = -(zoomedW - Double(outW)) * tf.offsetX
                let drawY = -(zoomedH - Double(outH)) * (1.0 - tf.offsetY)
                ctx.draw(cgImage, in: CGRect(x: drawX, y: drawY, width: zoomedW, height: zoomedH))
            }
            CVPixelBufferUnlockBaseAddress(pb, [])
            let pts = CMTime(value: Int64(frame), timescale: CMTimeScale(fps))
            adaptor.append(pb, withPresentationTime: pts)
        }

        input.markAsFinished()
        let sem = DispatchSemaphore(value: 0)
        writer.finishWriting { sem.signal() }
        sem.wait()
        if writer.status != .completed {
            throw LivePhotoEngineError.writerFailed(writer.error?.localizedDescription ?? "MOV 写入失败")
        }
    }
}
