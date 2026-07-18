# 相册投送中控 V3

把电脑上的整批作品通过同一 Wi‑Fi 送到 Android 手机。Android 应用名称为“相册”，不需要填写 IP、配对码，也不需要 Node.js、命令行、ADB、Root、无障碍或管理员权限。

## 最短使用流程

1. 手机打开“相册”，电脑打开原生 Windows 面板。
2. 把一个 ZIP 拖到手机卡片。
3. 手机自动把 ZIP 中每个“图片 + TXT”的子文件夹识别为一个作品。
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

读取规则与 ZIP 相同。文件会导入 App 私有作品库，避免目标平台在分享过程中失去读取权限。

## 作品状态和回收站

- 打开系统分享目标后，作品显示“已打开分享”。
- 当天保留在作品列表。
- 下一自然日自动进入回收站。
- 回收站可恢复，进入 7 天后自动清理。

## Windows 面板

- 纯 Win32 C++，不依赖浏览器、Node.js、Python 或 .NET。
- 自动发现同一 Wi‑Fi 的手机。
- 拖放后显示真实进度条、百分比、已传大小、总大小和速度。
- 支持取消正在进行的传送。
- 显示清晰错误，并可打开诊断日志。

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

已实现未验收：

- Lark 目录存在真实“图片 + TXT”作品时的批量导入；
- 次日自动进入回收站与 7 天后自动清理的跨日实体机行为；
- Windows 新版可视进度条和取消按钮（本机缺少 MSVC，等待 CI 构建产物后验收）。

单元测试已覆盖作品判定、自然排序、ZIP 路径安全、Windows 反斜杠 ZIP、次日回收、7 天清理和恢复。未经过实体手机的项目不会标成“测试通过”。

## 构建

GitHub Actions 负责：

- Windows x64 原生便携版；
- Android `testDebugUnitTest`、`assembleDebug`、`lintDebug`；
- 仓库质量检查和密钥扫描。

设计与边界见 [docs/ALBUM_WORKFLOW_DESIGN.md](docs/ALBUM_WORKFLOW_DESIGN.md)。
