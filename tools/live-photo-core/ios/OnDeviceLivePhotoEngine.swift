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

enum LiveCoverMode: String {
    case losslessCopy = "lossless_copy"
    case transcoded = "transcoded"
}

struct LivePhotoPair {
    let imageURL: URL
    let videoURL: URL
}

struct LiveConvertResult {
    let pair: LivePhotoPair
    let coverMode: LiveCoverMode
    let reencodeReason: String?
    let coverWidth: Int
    let coverHeight: Int
    let coverBytes: Int
    let videoWidth: Int
    let videoHeight: Int
    let videoBitrateBps: Int
    let requestedTier: LiveQualityTier
    let effectiveTier: LiveQualityTier
    let downgradeReason: String?
    let elapsedMs: Int
}

/// Primary shared on-device hardware AVAssetWriter Live Photo engine (iOS 12.0+ safe).
/// Static covers for JPEG/HEIC inputs copy compressed image data untouched via CGImageDestinationCopyImageSource;
/// dynamic video renders independently in 1080P/1440P/2048P quality tiers with automatic fallback.
final class OnDeviceLivePhotoEngine {
    static let shared = OnDeviceLivePhotoEngine()
    private init() {}

    /// Reads exact untouched original photo bytes from PHAsset without resizing/re-encoding.
    func requestOriginalPhotoData(for asset: PHAsset, completion: @escaping (Data?) -> Void) {
        let resources = PHAssetResource.assetResources(for: asset)
        let primary = resources.first(where: { $0.type == .photo })
            ?? resources.first(where: { $0.type == .alternatePhoto })
        if let res = primary {
            let opts = PHAssetResourceRequestOptions()
            opts.isNetworkAccessAllowed = true
            let buffer = NSMutableData()
            PHAssetResourceManager.default().requestData(for: res, options: opts, dataReceivedHandler: { chunk in
                buffer.append(chunk)
            }, completionHandler: { error in
                if error == nil && buffer.length > 0 {
                    DispatchQueue.main.async { completion(buffer as Data) }
                } else {
                    self.requestOriginalDataFallback(asset: asset, completion: completion)
                }
            })
            return
        }
        requestOriginalDataFallback(asset: asset, completion: completion)
    }

    private func requestOriginalDataFallback(asset: PHAsset, completion: @escaping (Data?) -> Void) {
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.isNetworkAccessAllowed = true
        opts.version = .current
        PHImageManager.default().requestImageData(for: asset, options: opts) { data, _, _, _ in
            DispatchQueue.main.async {
                completion(data)
            }
        }
    }

    func convertDataToLivePairDetailed(
        sourceData: Data,
        preset: AutoLiveMotionMode = AutoLivePreferences.motionMode,
        qualityTier: LiveQualityTier = AutoLivePreferences.qualityTier,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LiveConvertResult {
        let t0 = Date()
        guard let imgSource = CGImageSourceCreateWithData(sourceData as CFData, nil) else {
            throw LivePhotoEngineError.invalidImage
        }
        let assetId = UUID().uuidString
        let stageDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("album-live-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: stageDir, withIntermediateDirectories: true)

        let srcUti = (CGImageSourceGetType(imgSource) as String?) ?? (kUTTypeJPEG as String)
        let isHeic = srcUti.lowercased().contains("heic") || srcUti.lowercased().contains("heif")
        let isJpeg = srcUti == (kUTTypeJPEG as String)
        let coverExt = isHeic ? "heic" : "jpg"
        let jpgURL = stageDir.appendingPathComponent("live_cover.\(coverExt)")
        let movURL = stageDir.appendingPathComponent("live_motion.mov")

        let props = CGImageSourceCopyPropertiesAtIndex(imgSource, 0, nil) as? [String: Any]
        var coverW = props?[kCGImagePropertyPixelWidth as String] as? Int ?? 0
        var coverH = props?[kCGImagePropertyPixelHeight as String] as? Int ?? 0

        var coverMode: LiveCoverMode = .transcoded
        var reencodeReason: String?

        if (isJpeg || isHeic),
           try writeLosslessCopyCover(source: imgSource, uti: srcUti as CFString, assetId: assetId, destination: jpgURL) {
            coverMode = .losslessCopy
        } else {
            reencodeReason = (isJpeg || isHeic) ? "copy_image_source_rejected" : "non_jpeg_heic_source_transcoded"
            guard let fullCg = CGImageSourceCreateImageAtIndex(imgSource, 0, [
                kCGImageSourceShouldCache: false
            ] as CFDictionary) else {
                throw LivePhotoEngineError.invalidImage
            }
            coverW = fullCg.width
            coverH = fullCg.height
            let fallbackJpgURL = stageDir.appendingPathComponent("live_cover.jpg")
            try writeHighQualityCover(cgImage: fullCg, sourceProps: props, assetId: assetId, destination: fallbackJpgURL)
        }

        let finalCoverURL = FileManager.default.fileExists(atPath: jpgURL.path)
            ? jpgURL
            : stageDir.appendingPathComponent("live_cover.jpg")
        let coverBytes = (try? Data(contentsOf: finalCoverURL).count) ?? sourceData.count

        // Decode an upright image independently for the dynamic MOV video track
        let videoMaxSide = max(qualityTier.targetLongSide * 2, 2048)
        let thumbOpts: [String: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            kCGImageSourceShouldCacheImmediately as String: true,
            kCGImageSourceCreateThumbnailWithTransform as String: true,
            kCGImageSourceThumbnailMaxPixelSize as String: videoMaxSide
        ]
        guard let videoCg = CGImageSourceCreateThumbnailAtIndex(imgSource, 0, thumbOpts as CFDictionary)
                ?? CGImageSourceCreateImageAtIndex(imgSource, 0, nil) else {
            throw LivePhotoEngineError.invalidImage
        }

        let vidRes = try writeMotionMOVWithFallback(
            cgImage: videoCg,
            preset: preset,
            requestedTier: qualityTier,
            ratio: ratio,
            duration: duration,
            assetId: assetId,
            destination: movURL
        )

        let elapsedMs = max(1, Int(Date().timeIntervalSince(t0) * 1000.0))
        return LiveConvertResult(
            pair: LivePhotoPair(imageURL: finalCoverURL, videoURL: movURL),
            coverMode: coverMode,
            reencodeReason: reencodeReason,
            coverWidth: coverW,
            coverHeight: coverH,
            coverBytes: coverBytes,
            videoWidth: vidRes.width,
            videoHeight: vidRes.height,
            videoBitrateBps: vidRes.bitrate,
            requestedTier: qualityTier,
            effectiveTier: vidRes.effectiveTier,
            downgradeReason: vidRes.downgradeReason,
            elapsedMs: elapsedMs
        )
    }

    func convertToLivePair(
        image: UIImage,
        preset: AutoLiveMotionMode = AutoLivePreferences.motionMode,
        qualityTier: LiveQualityTier = AutoLivePreferences.qualityTier,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LivePhotoPair {
        let upright = normalizeUpright(image)
        if let jpgData = upright.jpegData(compressionQuality: 0.98) {
            return try convertDataToLivePairDetailed(
                sourceData: jpgData,
                preset: preset,
                qualityTier: qualityTier,
                ratio: ratio,
                duration: duration
            ).pair
        }
        guard let cgImage = upright.cgImage else {
            throw LivePhotoEngineError.invalidImage
        }
        let assetId = UUID().uuidString
        let stageDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("album-live-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: stageDir, withIntermediateDirectories: true)
        let jpgURL = stageDir.appendingPathComponent("live_cover.jpg")
        let movURL = stageDir.appendingPathComponent("live_motion.mov")
        try writeHighQualityCover(cgImage: cgImage, sourceProps: nil, assetId: assetId, destination: jpgURL)
        _ = try writeMotionMOVWithFallback(
            cgImage: cgImage,
            preset: preset,
            requestedTier: qualityTier,
            ratio: ratio,
            duration: duration,
            assetId: assetId,
            destination: movURL
        )
        return LivePhotoPair(imageURL: jpgURL, videoURL: movURL)
    }

    func convertFileToLivePair(
        imageURL: URL,
        preset: AutoLiveMotionMode = AutoLivePreferences.motionMode,
        qualityTier: LiveQualityTier = AutoLivePreferences.qualityTier,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LivePhotoPair {
        guard let data = try? Data(contentsOf: imageURL) else {
            throw LivePhotoEngineError.invalidImage
        }
        return try convertDataToLivePairDetailed(
            sourceData: data,
            preset: preset,
            qualityTier: qualityTier,
            ratio: ratio,
            duration: duration
        ).pair
    }

    func convertAndSaveDataToPhotos(
        sourceData: Data,
        preset: AutoLiveMotionMode = AutoLivePreferences.motionMode,
        qualityTier: LiveQualityTier = AutoLivePreferences.qualityTier,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LiveConvertResult {
        let res = try convertDataToLivePairDetailed(
            sourceData: sourceData,
            preset: preset,
            qualityTier: qualityTier,
            ratio: ratio,
            duration: duration
        )
        try savePairsToPhotos([res.pair])
        return res
    }

    func convertAndSaveToPhotos(
        image: UIImage,
        preset: AutoLiveMotionMode = AutoLivePreferences.motionMode,
        qualityTier: LiveQualityTier = AutoLivePreferences.qualityTier,
        ratio: Double = AutoLivePreferences.motionRatio,
        duration: Double = AutoLivePreferences.motionDuration
    ) throws -> LivePhotoPair {
        let pair = try convertToLivePair(
            image: image,
            preset: preset,
            qualityTier: qualityTier,
            ratio: ratio,
            duration: duration
        )
        try savePairsToPhotos([pair])
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

    private func normalizeUpright(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: image.size, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    private func writeLosslessCopyCover(
        source: CGImageSource,
        uti: CFString,
        assetId: String,
        destination: URL
    ) throws -> Bool {
        guard let dest = CGImageDestinationCreateWithURL(destination as CFURL, uti, 1, nil) else {
            return false
        }
        let makerApple: [String: Any] = ["17": assetId]
        let opts: [String: Any] = [
            kCGImageDestinationMergeMetadata as String: true,
            kCGImagePropertyMakerAppleDictionary as String: makerApple
        ]
        var cfErr: Unmanaged<CFError>?
        let ok = CGImageDestinationCopyImageSource(dest, source, opts as CFDictionary, &cfErr)
        if !ok {
            _ = cfErr?.takeRetainedValue()
            try? FileManager.default.removeItem(at: destination)
            return false
        }
        return true
    }

    private func writeHighQualityCover(
        cgImage: CGImage,
        sourceProps: [String: Any]?,
        assetId: String,
        destination: URL
    ) throws {
        guard let dest = CGImageDestinationCreateWithURL(
            destination as CFURL,
            kUTTypeJPEG,
            1,
            nil
        ) else {
            throw LivePhotoEngineError.writerFailed("无法创建 JPEG 输出")
        }
        var props = sourceProps ?? [:]
        var maker = (props[kCGImagePropertyMakerAppleDictionary as String] as? [String: Any]) ?? [:]
        maker["17"] = assetId
        props[kCGImageDestinationLossyCompressionQuality as String] = 0.98
        props[kCGImagePropertyMakerAppleDictionary as String] = maker
        CGImageDestinationAddImage(dest, cgImage, props as CFDictionary)
        if !CGImageDestinationFinalize(dest) {
            throw LivePhotoEngineError.writerFailed("写入 Live JPEG 元数据失败")
        }
    }

    private func writeMotionMOVWithFallback(
        cgImage: CGImage,
        preset: AutoLiveMotionMode,
        requestedTier: LiveQualityTier,
        ratio: Double,
        duration: Double,
        assetId: String,
        destination: URL
    ) throws -> (width: Int, height: Int, bitrate: Int, effectiveTier: LiveQualityTier, downgradeReason: String?) {
        var cur: LiveQualityTier? = requestedTier
        var lastErr: Error?
        var notes: [String] = []
        while let tier = cur {
            let dims = tier.computeEncodedSize(srcW: cgImage.width, srcH: cgImage.height)
            let bps = tier.computeBitrateBps(width: dims.width, height: dims.height)
            do {
                try? FileManager.default.removeItem(at: destination)
                try writeMotionMOV(
                    cgImage: cgImage,
                    preset: preset,
                    outW: dims.width,
                    outH: dims.height,
                    bitrateBps: bps,
                    ratio: ratio,
                    duration: duration,
                    assetId: assetId,
                    destination: destination
                )
                return (dims.width, dims.height, bps, tier, notes.isEmpty ? nil : notes.joined(separator: " -> "))
            } catch {
                lastErr = error
                if let lower = tier.nextLower {
                    notes.append("\(tier.rawValue) 编码失败(\(error.localizedDescription))降级至 \(lower.rawValue)")
                    cur = lower
                } else {
                    throw error
                }
            }
        }
        throw lastErr ?? LivePhotoEngineError.writerFailed("MOV 编码失败")
    }

    private func writeMotionMOV(
        cgImage: CGImage,
        preset: AutoLiveMotionMode,
        outW: Int,
        outH: Int,
        bitrateBps: Int,
        ratio: Double,
        duration: Double,
        assetId: String,
        destination: URL
    ) throws {
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
                AVVideoAverageBitRateKey: bitrateBps
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
