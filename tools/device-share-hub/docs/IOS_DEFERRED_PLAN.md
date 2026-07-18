# iOS 客户端延期开发存档

日期：2026-07-18  
状态：已完成可行性分析，暂缓开发。仓库中尚无 iOS 工程、IPA 或经过 iPhone 验收的功能。

## 决策

iOS 客户端以后作为现有 `device-share-hub` 的第三个客户端增加，不新建另一套产品，不改变 Windows + Android 已有流程。界面继续使用“相册”名称，并坚持同一 Wi-Fi 自动发现、无手动 IP、无配对码、无命令行操作。

推荐的自用安装方式是 Apple Developer Program + TestFlight：

- 仅自己或少量内部账号使用时，优先 TestFlight 内部测试；
- 需要通过链接给更多设备安装时，使用 TestFlight 外部测试公开链接，首个外部测试版本需要 Beta App Review；
- TestFlight 构建有有效期，需要定期上传新版；
- 免费 Personal Team 仅适合短期开发调试，设备上的开发签名通常 7 天到期，不符合“越简单越好”的长期使用目标。

官方参考：

- [Apple TestFlight](https://developer.apple.com/testflight/)
- [邀请 TestFlight 外部测试人员](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers)
- [Apple Developer 账号与 Personal Team 限制](https://developer.apple.com/help/account/basics/about-your-developer-account)

## 预期最短流程

```text
Windows 拖入 ZIP / 单个作品文件夹
→ 同一 Wi-Fi 自动发现 iPhone
→ iPhone 前台接收并导入 App 私有作品库
→ 用户点一个作品
→ 自动复制 TXT 文案 + 选中全部图片 + 打开 iOS 系统分享面板
→ 用户选择平台、粘贴文案并确认发布
```

不得尝试自动点击目标平台、自动发布、伪装系统相册来源或规避平台检测。

## 文件读取方案

电脑新传入的作品直接进入 App 私有作品库，这是默认路径，不要求用户操作“文件”App。

如需复用 iPhone 上已有素材：

1. 用户通过 `UIDocumentPickerViewController` 选择一次作品目录；
2. App 保存 security-scoped bookmark；
3. 后续启动时恢复授权并递归识别“图片 + TXT”作品文件夹；
4. 授权失效、文件提供器离线或目录被移动时，显示清晰提示并允许重新选择。

官方目录访问机制：[Providing access to directories](https://developer.apple.com/documentation/uikit/providing-access-to-directories)。

iOS 不允许应用无授权遍历整个 Downloads，也不能保证直接读取 Lark 私有目录。若 Lark 在系统“文件”中提供可选目录，则由用户授权；否则优先使用电脑直传到 App 私有库。

## 技术边界

- 需要 macOS、Xcode 和苹果签名环境才能生成可安装真机包；仅连接 iPhone 到 Windows 不能完成正式构建和签名。
- 可以使用 CI 的 macOS 构建机做自动化构建，但真机签名和 TestFlight 上传仍需要 Apple Developer / App Store Connect 凭据。
- 局域网发现需要声明 Local Network / Bonjour 用途并处理用户授权。
- iOS 后台网络接收受系统限制：第一版要求“相册”保持前台接收；后台唤醒能力不能先行承诺。
- 分享使用系统 `UIActivityViewController`；系统只能确认分享页或目标应用被打开，不能可靠确认平台发布成功。
- 图片保持原始字节，不注入虚假 EXIF、位置、相机型号或拍摄参数。

## 计划复用的现有能力

- 复用当前局域网发现和传输协议的语义；如 iOS 网络框架需要调整，只做向后兼容扩展。
- 复用 ZIP 安全规则、作品判定、自然排序、TXT 优先级、分享计数、次日回收和 7 天保留规则。
- Windows 面板应同时显示 Android 与 iPhone 设备，设备类型由发现信息区分，不增加用户配置步骤。

## 恢复开发时的前置条件

1. 一台可使用的 Mac，安装当时稳定版 Xcode；
2. 一台实体 iPhone 和对应数据线；
3. Apple Developer Program 账号；
4. 确定 Bundle ID、签名团队和 TestFlight 使用的 Apple ID；
5. 在仓库 `tools/device-share-hub/ios` 内创建工程；
6. 先完成前台接收、作品列表、复制文案和系统分享的最小闭环，再做目录授权与回收站。

## 验收状态

以下全部为“尚未实现、尚未验收”：

- iOS 工程与自动化构建；
- iPhone 同一 Wi-Fi 自动发现和前台接收；
- ZIP / 文件夹作品导入；
- Files/Lark 目录授权和持久访问；
- 文案复制、多图系统分享；
- 分享计数、次日回收和 7 天清理；
- TestFlight 签名、上传、链接安装及更新。

没有实体 iPhone 测试记录前，不得宣称任何 iOS 功能测试通过。
