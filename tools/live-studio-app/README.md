# 实况工坊（LiveStudio）独立手机应用

纯本地离线、零 FFmpeg 体积膨胀的独立实况照片制作与分享应用（Android `com.zwm.livestudio` / iOS `com.zwm.livestudio`，v1.1.0），与「团队工作相册」共用 `tools/live-photo-core/` 原画无损静态实况核心：
- 静态封面采用纯元数据封装（JPEG/HEIC 零解码重压缩、零尺寸缩水、完整保留 EXIF 方向与 ICC），PNG/WebP 全分辨率高质量保真转换；
- 动态视频支持 3 档画质（流畅 `1080P` / 高清 `1440P` 默认 / 原画 `2048P`）与硬件编码器自动降级，支持单张失败重试与无损状态报告。

## 核心能力
- **本地相册式选图**：直接读取手机本地相册网格浏览、按目录筛选、多选或通过系统分享把图片送入「实况工坊」。
- **纯硬件离线生成**：
  - Android：OpenGL ES 2.0 亚像素双线性插值 + `MediaCodec` (`video/avc`) + `MediaMuxer` 硬件编码 MP4，纯 Java 封装 Google Motion Photo V1/V2 XMP 写入系统相册 `Pictures/实况工坊`。
  - iOS：`CoreGraphics` + `AVAssetWriter` (H.264 `.mov`) + `ImageIO` (`ContentIdentifier`) + `PHAssetCreationRequest` 保存原生 Live Photo。
- **9 种自然微动预设**：默认「自然手持微动（推荐）」(`1.018x` / `1.6s`) 与「几乎静止（极微呼吸）」，外加轻柔推近/拉远、四向微移与温和手持轻晃，可在顶部效果栏直接切换。
- **即转即分享**：一键「转实况并分享」，直接拉起系统分享给小红书、抖音或微信，对方收到即识别为动态实况照片。

## 本地构建 Android Release
```powershell
Set-Location tools/live-studio-app/android
gradle :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```
