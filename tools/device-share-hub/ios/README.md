# 相册 iPhone 客户端（AltStore 自用版）

版本：0.6.17 / build 36（Beta）

状态：Beta 测试版本；云端构建和当前轮次真机记录见维护交接

0.6.17 继续用同一个安装包覆盖 iOS 12 及以上，并在 `/v2/info` 上报与 Android/Windows 一致的分类库存。已移除自动截图识别、截图发送、自动读取/同步系统剪切板和相册读取权限；顶部分类统一显示“精准流量”和“泛流量”，作品卡片提供左对齐的“预览 / 发抖音 / 发小红书”纵向紧凑按钮，点击后对应平台按钮立即变灰但仍可点击，预览页可左右滑动查看下一张。检查到新版本时可直接打开 AltStore 或复制更新源。普通文件、图片导入、系统分享与局域网传送不变。此 Beta 还包含远程中继控制面和会话心跳基础，尚未完成真实跨网络收发验收。iPhone 6 使用应用自己的固定作品库；iOS 13 及以上继续支持系统文件夹选择与完整文件夹传送。应用离开前台后不承诺持续在线。

这是现有“素材投送中控”的 iPhone 客户端，不是另一个项目。它提供递归作品列表、复制文案、多图系统分享、打开分享次数、次日回收和回收站保留 7 天。

## 下载构建产物

每次推送后，在 GitHub 仓库的 Actions 页面打开最新的 `Device Share Hub` 工作流，下载与工程版本对应的 `album-ios-altstore-v<版本>` artifact，解压得到：

- `album-iOS-v<版本>-altstore.ipa`
- `album-iOS-v<版本>-altstore.ipa.sha256`

CI 产物没有预置任何人的 Apple 证书、账号或设备信息。侧载工具安装时会用你自己的 Apple ID 重新签名。

IPA 的 SHA-256 以同一 artifact 内的 `.sha256` 文件为准；实体检查完成后再创建正式 GitHub Release。

完整的 Windows 安装、排障和踩坑记录见 [../docs/IOS_WINDOWS_SIDELOAD_HANDOFF.md](../docs/IOS_WINDOWS_SIDELOAD_HANDOFF.md)。

## Windows + Sideloadly 安装（备用）

1. 安装 Apple 官网版本的 iTunes 和 iCloud。
2. 数据线连接、解锁 iPhone 并选择“信任此电脑”；先确认 iTunes 能显示 iPhone。
3. 在 Sideloadly 0.60.0 或更高兼容版本中选择 iPhone、加载 IPA。
4. 用户本人输入 Apple ID 密码，等待 `Done. 100%`。
5. 按 iOS 提示信任开发者；iOS 16 及以上还需开启开发者模式，然后打开“相册”。
6. 首次打开时允许“本地网络”；传送期间让“相册”保持前台，电脑会自动显示手机名称。

免费证书有效期为 7 天；保持电脑和手机处于同一 Wi-Fi，并让 Sideloadly 后台续签服务运行。

推荐只让轻量 `sideloadlydaemon` 随 Windows 登录启动；它按约 96 小时刷新，主界面平时可以关闭。电脑不必一直开机，但不能连续超过 7 天都没有一次满足“电脑开机 + 手机同一 Wi-Fi”的续签机会。

## Windows + AltStore 安装（推荐）

1. 按 AltStore 官方说明安装 Apple 官网版本的 iTunes、iCloud 和 AltServer。
2. 第一次用数据线连接 iPhone，在 iTunes 中开启 Wi-Fi 同步。
3. 用 AltServer 把 AltStore 安装到 iPhone；iOS 16 及以上开启“开发者模式”。
4. 把 IPA 保存到 iPhone“文件”，在 AltStore 的 `My Apps` 页点 `+` 并选择 IPA。
5. 打开“相册”。iOS 13 及以上只选择一次固定作品总文件夹；iOS 12 直接使用应用内固定文件夹。

### AltStore 自动发现更新

首次在 AltStore 的 Sources 中添加以下地址：

`https://raw.githubusercontent.com/zwmopen/gallery-updates/main/altstore.json`

之后每次云端发布新的 iPhone IPA，AltStore 会从该源自动发现新版本；AltServer 在同一 Wi-Fi 下负责侧载和免费证书刷新。更新安装仍需在 AltStore 中确认一次，iOS 不允许应用静默替换自身。

遇到问题时，在“设置 → 复制诊断信息”取得版本、iOS、授权状态、作品数量和最近错误；诊断内容不包含文案、图片内容或完整文件路径。

AltServer 只负责侧载和免费证书刷新；应用本身不需要管理员、越狱、设备管理、ADB、手动 IP 或配对码。

## iPhone 6 与新 iPhone 的目录差异

- iOS 12（iPhone 6）：系统不提供“授权整个外部文件夹”的能力。应用自动读取“文件 → 我的 iPhone → 相册”，也可用 iTunes 文件共享把整个作品目录放进去。
- iOS 13 及以上：既可在系统文件选择器中选任意总文件夹，也可在设置中切回“我的 iPhone/相册”。
- 这是同一个 `com.zwm.album` 安装包和同一套作品逻辑，不是按手机型号拆分的版本。

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

- 最多递归检查 8 层、1000 个文件夹；作品可以位于“总文件夹/作品包/单个作品”结构中。
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

## 验收记录

0.3.0 的递归扫描、协议解析、SHA-256 和文件夹 ZIP 安全展开用例已进入 GitHub Actions；本次 Windows、Android、iPhone 三个构建任务全部成功。实体 iPhone 已完成签名安装并确认 0.3.0 进程启动；局域网发现和传送必须在手机解锁、应用前台并允许“本地网络”后记录结果。
