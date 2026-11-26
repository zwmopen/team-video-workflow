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

final class LivePhotoEngine {
    static let shared = LivePhotoEngine()
    private init() {}

    func convertAndSaveToPhotos(
        image: UIImage,
        preset: MotionPreset,
        ratio: Double,
        duration: Double
    ) throws -> LivePhotoPair {
        let upright = normalizeUpright(image)
        guard let cgImage = upright.cgImage else {
            throw LivePhotoEngineError.invalidImage
        }
        let assetId = UUID().uuidString
        let stageDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("livestudio-\(UUID().uuidString)", isDirectory: true)
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

        var saveError: Error?
        let sem = DispatchSemaphore(value: 0)
        PHPhotoLibrary.shared().performChanges({
            let req = PHAssetCreationRequest.forAsset()
            let imgOpts = PHAssetResourceCreationOptions()
            imgOpts.shouldMoveFile = false
            let vidOpts = PHAssetResourceCreationOptions()
            vidOpts.shouldMoveFile = false
            req.addResource(with: .photo, fileURL: jpgURL, options: imgOpts)
            req.addResource(with: .pairedVideo, fileURL: movURL, options: vidOpts)
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
        return LivePhotoPair(imageURL: jpgURL, videoURL: movURL)
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
        preset: MotionPreset,
        ratio: Double,
        duration: Double,
        assetId: String,
        destination: URL
    ) throws {
        let srcW = cgImage.width
        let srcH = cgImage.height
        let longSide = max(srcW, srcH)
        let scale = longSide > 1080 ? (1080.0 / Double(longSide)) : 1.0
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
                AVVideoAverageBitRateKey: max(1_800_000, min(6_000_000, outW * outH * 4))
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

        let fps = MotionPreset.defaultFPS
        let totalFrames = max(12, Int((MotionPreset.clampDuration(duration) * Double(fps)).rounded()))
        let imgSize = CGSize(width: srcW, height: srcH)
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
                let crop = preset.cropRect(imageSize: imgSize, ratio: ratio, frameIndex: frame, totalFrames: totalFrames)
                if let sub = cgImage.cropping(to: crop) {
                    ctx.draw(sub, in: CGRect(x: 0, y: 0, width: outW, height: outH))
                } else {
                    ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: outW, height: outH))
                }
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
