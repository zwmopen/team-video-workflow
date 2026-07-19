# 相册 iPhone 客户端（AltStore 自用版）

版本：0.1.0  
状态：已实现未验收

这是现有“素材投送中控”的 iPhone 客户端，不是另一个项目。第一版读取用户在系统文件选择器中授权的固定作品总文件夹，提供作品列表、复制文案、多图系统分享、打开分享次数、次日回收和回收站保留 7 天。

## 下载构建产物

每次推送后，在 GitHub 仓库的 Actions 页面打开最新的 `Device Share Hub` 工作流，下载 `album-ios-altstore-v0.1.0` artifact，解压得到：

- `album-iOS-v0.1.0-altstore.ipa`
- `album-iOS-v0.1.0-altstore.ipa.sha256`

CI 产物没有预置任何人的 Apple 证书、账号或设备信息。AltStore 安装时会用你自己的 Apple ID 重新签名。

## Windows + AltStore 安装

1. 按 AltStore 官方说明安装 Apple 官网版本的 iTunes、iCloud 和 AltServer。
2. 第一次用数据线连接 iPhone，在 iTunes 中开启 Wi-Fi 同步。
3. 用 AltServer 把 AltStore 安装到 iPhone；iOS 16 及以上开启“开发者模式”。
4. 把 IPA 保存到 iPhone“文件”，在 AltStore 的 `My Apps` 页点 `+` 并选择 IPA。
5. 打开“相册”，只选择一次固定作品总文件夹。

AltServer 只负责侧载和免费证书刷新；应用本身不需要管理员、越狱、设备管理、ADB、手动 IP 或配对码。

## 作品规则

```text
作品总文件夹/
├─ 作品一/
│  ├─ 文案.txt
│  ├─ 01.jpg
│  └─ 02.jpg
├─ 作品二/
│  ├─ 其他名称.txt
│  └─ 01.png
├─ _相册回收站/
└─ _相册状态.json
```

- 只检查作品总文件夹的直接子文件夹，不把复杂聚合目录误判为作品。
- 同时包含至少一张 JPG/JPEG/PNG/WEBP/HEIC/HEIF 和一个 TXT 才显示。
- 优先使用 `文案.txt`，否则按名称使用第一个 TXT。
- 分享次数指“已打开系统分享”，不代表目标平台发布成功。
- 每次打开或下拉刷新时执行维护：上一天已分享作品进入回收站，进入回收站满 7 天后清理。

## 构建

工程由 `project.yml` 通过 XcodeGen 生成。云端构建命令已写入 `.github/workflows/device-share-hub.yml`。本地有 macOS/Xcode 时可执行：

```bash
cd tools/device-share-hub/ios
xcodegen generate
xcodebuild -project Album.xcodeproj -scheme Album -sdk iphoneos build
```

Windows 不需要运行这些命令。

## 验收声明

Windows 环境无法运行 Xcode 或 iPhone 模拟器。GitHub Actions 编译成功也只代表 IPA 已生成，不代表 AltStore 安装、Lark 目录权限、多图分享、跨日回收或 7 天清理通过实体 iPhone 验收。完成真机逐项操作前，全部保持“已实现未验收”。

