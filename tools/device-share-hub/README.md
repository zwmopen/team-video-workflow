# 相册投送中控 V3

把电脑上的整批作品送到 Android 或 iPhone。Android 继续通过同一 Wi‑Fi 自动接收；iPhone 第一版读取用户手动传入并解压的固定作品文件夹。两端应用都叫“相册”，不需要填写 IP、配对码，也不需要 Node.js、命令行、ADB、Root、无障碍或设备管理权限。

## iPhone 0.1.2（自用侧载版）

仓库已经加入原生 SwiftUI iPhone 客户端和快捷指令交付方案：

- 原生版：选择一次固定作品总文件夹，两列显示作品；点击后复制 TXT、记录“已打开分享 N 次”并把全部图片交给 iOS 系统分享面板；次日运行时回收，回收站保留 7 天。
- 安装版：GitHub Actions 的 macOS runner 生成未预签名 IPA，可由 Windows Sideloadly 或 AltStore 使用用户自己的 Apple ID 安装和续签。
- 快捷指令版：动作与状态设计已完成；正式 iCloud 安装链接必须在实体 iPhone 上搭建、验收并由苹果验证后导出。

iPhone 代码、安装说明见 [ios/README.md](ios/README.md)，Windows 侧载交付和排障沉淀见 [docs/IOS_WINDOWS_SIDELOAD_HANDOFF.md](docs/IOS_WINDOWS_SIDELOAD_HANDOFF.md)，快捷指令交付清单见 [ios/shortcut/README.md](ios/shortcut/README.md)。0.1.1 已完成实体 iPhone 签名安装；0.1.2 的隐藏目录修复完成后仍需重新安装验收，所有业务功能继续标记“已实现未验收”。

## 最短使用流程

1. 手机打开“相册”，电脑打开原生 Windows 面板。
2. 把一个 ZIP 或单个作品文件夹拖到手机卡片；文件夹由电脑自动打包，不需要手动压缩。复杂的作品合集请直接拖 ZIP。
3. 手机自动展开，并把每个“图片 + TXT”的子文件夹识别为一个作品。
4. 点作品的“复制文案并分享”。
5. App 复制 TXT 文案、带上全部图片并打开 Android 系统分享面板。

Android 无法知道用户是否在目标平台完成发布，因此状态诚实显示为“已打开分享”，不宣称“发布成功”。

## ZIP 作品规则

- 递归寻找子文件夹。
- 一个子文件夹的直接内容同时包含至少一张支持的图片和一个 `.txt`，才判定为作品。
- 图片支持 JPG、JPEG、PNG、WEBP、HEIC、HEIF。
- 文案优先使用 `文案.txt`；否则按自然顺序使用第一个 TXT。
- 图片按自然顺序排列，例如 `2.jpg` 在 `10.jpg` 前面。
- 多个 TXT 会显示提示。
- 拒绝 ZIP 路径越界，并限制作品数、单作品图片数和文件大小。

## 手机文件夹

第一次点“选择 Lark 文件夹”，使用 Android 系统文件夹选择器授权一个目录。App 保存该授权，以后点“刷新”即可递归读取。Android 11 以后通常不允许第三方应用直接授权整个 Download 根目录，因此优先选择 `Download/Lark` 或实际保存作品的子文件夹。

读取规则与 ZIP 相同。文件会导入 App 私有作品库，避免目标平台在分享过程中失去读取权限。已授权可写目录时，电脑新传入的 ZIP/文件夹还会自动解压一份到该目录；外部目录同步失败不会破坏 App 内已接收的作品。

## 作品状态和回收站

- 每次打开系统分享目标后，作品显示“已打开分享 N 次”。旧版本已有的分享记录从升级后的首次再次分享开始计数。
- 当天保留在作品列表。
- 下一自然日自动进入回收站。
- 回收站可恢复，进入 7 天后自动清理。

## Windows 面板

- 纯 Win32 C++，不依赖浏览器、Node.js、Python 或 .NET。
- 自动发现同一 Wi‑Fi 的手机。
- 支持直接拖 ZIP、单个作品文件夹或图片。单个作品文件夹必须直接包含图片和 TXT；含嵌套子目录的复杂文件夹会明确拒绝，作品合集请拖 ZIP。
- 直接拖图片时，手机前台自动打开系统分享；手机在后台时点接收通知即可进入分享。
- 单文件上限 4GB；这是当前 WinHTTP 与 ZIP32 实现的明确边界。
- 拖放后显示真实进度条、百分比、已传大小、总大小和速度。
- 支持取消正在进行的传送。
- 显示清晰错误，并可打开诊断日志。

## 设置与图片信息

- 设置页只保留手机名称、作品文件夹、当前版本、检查更新和软件说明。手机名称会显示在电脑端。
- App 每天静默检查一次 GitHub Release；也可在设置中手动检查。只提示，不静默安装。
- 传输和分享保持图片原始字节，因此已有 EXIF 拍摄时间、相机参数和位置会原样保留。
- 不注入虚假的相机、地址或拍摄参数，也不提供伪造元数据开关。

## Android 配置

- 应用名称：`相册`
- applicationId：`com.zwm.gallery`
- FileProvider：`com.zwm.gallery.files`
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`
- JDK 17 / Gradle 9.4.1

## 验收状态（2026-07-18）

已在实体 Redmi `24129PN74C` / Android 16 验收：

- 新包安装和“相册”主界面；
- 同一 Wi‑Fi 接收 Windows 生成的 ZIP；
- ZIP 自动解析为多个作品；
- 文案进入剪贴板并显示在系统分享页；
- 多图片分享 Intent；
- 从分享页返回后显示“已打开分享”；
- `Download/Lark` 持久授权可读取（测试时该目录为空，正确识别为 0 个作品）。
- 新接收 ZIP 自动解压同步到实体 Redmi 的 `Download/Lark`，文件名和文案内容正确；验证后已清理临时测试作品。
- 手机两列作品卡片、已分享整卡变灰、作品页/回收站按钮状态切换。
- 设置页显示手机名称、Lark 路径、当前版本、检查更新和软件说明。
- 同一作品连续打开分享两次，卡片显示“已打开分享 2 次”。

已实现未验收：

- Lark 目录存在用户真实“图片 + TXT”作品时的批量导入（测试目录当时为空）；
- 次日自动进入回收站与 7 天后自动清理的跨日实体机行为；
- Windows 文件夹自动打包拖放、可视进度条和取消按钮（CI 已编译，尚未完成新版 EXE 实际拖放验收）。
- Windows 直接拖图片后手机自动进入分享页。
- 手机名称修改后 Windows 端显示更新名称。

单元测试已覆盖作品判定、自然排序、ZIP 路径安全、Windows 反斜杠 ZIP、次日回收、7 天清理和恢复。未经过实体手机的项目不会标成“测试通过”。

## 构建

GitHub Actions 负责：

- Windows x64 原生便携版；
- Android `testDebugUnitTest`、`assembleDebug`、`lintDebug`；
- iOS SwiftUI 编译、侧载 IPA 打包及内部路径/版本结构校验；
- 仓库质量检查和密钥扫描。

发布说明、设计语言、开发资产和复用避坑见 [docs/V3_RELEASE_AND_ASSET_INVENTORY.md](docs/V3_RELEASE_AND_ASSET_INVENTORY.md)；设计与边界见 [docs/ALBUM_WORKFLOW_DESIGN.md](docs/ALBUM_WORKFLOW_DESIGN.md)。
