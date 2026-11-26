# 实况工坊（LiveStudio）独立手机应用

纯本地离线、零 FFmpeg 体积膨胀的独立实况照片制作与分享应用（Android `com.zwm.livestudio` / iOS `com.zwm.livestudio`，v1.0.0）。

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
