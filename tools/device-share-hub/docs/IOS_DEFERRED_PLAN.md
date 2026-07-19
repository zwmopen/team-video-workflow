# iOS 客户端开发存档

日期：2026-07-18  
状态：2026-07-19 已恢复开发。仓库中已有 SwiftUI 工程和 AltStore 云端构建流程，但尚无经过实体 iPhone 验收的功能。

2026-07-19 补充：快捷指令和 AltStore 原生客户端改为并行开发。快捷指令需要实体 iPhone 导出 iCloud 链接；原生客户端由 GitHub Actions 的 macOS runner 生成未预签名 IPA，再由 AltStore 使用用户 Apple ID 侧载。设计见仓库根目录 `docs/superpowers/specs/2026-07-19-ios-shortcut-work-share-design.md` 和 `docs/superpowers/specs/2026-07-19-ios-altstore-client-design.md`。

## 决策

iOS 客户端作为现有 `device-share-hub` 的第三个客户端增加，不新建另一套产品，不改变 Windows + Android 已有流程。界面继续使用“相册”名称。第一版由用户手动传入并解压 ZIP，应用读取固定作品目录，不增加手动 IP、配对码或命令行操作。

当前推荐的免费自用安装方式是 Windows AltStore Classic：

- GitHub Actions 的 macOS runner 负责把 SwiftUI 源码编译成未预签名 IPA；
- AltStore 在 Windows 和 iPhone 上使用用户自己的 Apple ID 重新签名、安装和续签；
- 第一次需要数据线、设备信任和 Wi-Fi 同步，之后由同一 Wi-Fi 下的 AltServer 刷新；
- 免费账号受 Apple 的签名有效期和最多 3 个活跃侧载 App 限制；
- 后续如改用 TestFlight，再增加 Apple Developer / App Store Connect 签名流程，不影响客户端代码。

官方参考：

- [Apple TestFlight](https://developer.apple.com/testflight/)
- [邀请 TestFlight 外部测试人员](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers)
- [Apple Developer 账号与 Personal Team 限制](https://developer.apple.com/help/account/basics/about-your-developer-account)

## 预期最短流程

```text
用户把 ZIP 传入 iPhone 并手动解压到固定作品总文件夹
→ 打开“相册”，首次选择一次总文件夹
→ 用户点一个作品
→ 自动复制 TXT 文案 + 选中全部图片 + 打开 iOS 系统分享面板
→ 用户选择平台、粘贴文案并确认发布
```

不得尝试自动点击目标平台、自动发布、伪装系统相册来源或规避平台检测。

## 文件读取方案

第一版复用 iPhone“文件”中已有素材：

1. 用户通过 `UIDocumentPickerViewController` 选择一次作品目录；
2. App 保存 security-scoped bookmark；
3. 后续启动时恢复授权，只检查总文件夹的直接子文件夹并识别“图片 + TXT”作品；
4. 授权失效、文件提供器离线或目录被移动时，显示清晰提示并允许重新选择。

官方目录访问机制：[Providing access to directories](https://developer.apple.com/documentation/uikit/providing-access-to-directories)。

iOS 不允许应用无授权遍历整个 Downloads，也不能保证直接读取 Lark 私有目录。若 Lark 在系统“文件”中提供可选目录，则由用户授权；否则使用“在我的 iPhone”或 iCloud Drive 中的固定目录。

## 技术边界

- 需要 macOS/Xcode 编译 iOS 源码；当前由 GitHub Actions 的 macOS runner 完成，Windows 本地不编译。
- CI 产出不带用户凭据的 IPA；AltStore 负责真机侧载签名。TestFlight 仍需要 Apple Developer / App Store Connect 凭据。
- 第一版没有局域网发现和后台接收，不声明这两项已实现。
- 分享使用系统 `UIActivityViewController`；系统只能确认分享页或目标应用被打开，不能可靠确认平台发布成功。
- 图片保持原始字节，不注入虚假 EXIF、位置、相机型号或拍摄参数。

## 计划复用的现有能力

- 复用当前局域网发现和传输协议的语义；如 iOS 网络框架需要调整，只做向后兼容扩展。
- 复用 ZIP 安全规则、作品判定、自然排序、TXT 优先级、分享计数、次日回收和 7 天保留规则。
- Windows 面板应同时显示 Android 与 iPhone 设备，设备类型由发现信息区分，不增加用户配置步骤。

## 实体机验收条件

1. 一台实体 iPhone 和对应数据线；
2. Windows 安装 AltServer、Apple 官网版本 iTunes/iCloud，并开启 Wi-Fi 同步；
3. 一个用于免费侧载的 Apple ID；
4. 下载 GitHub Actions 生成的 IPA；
5. 准备至少两个真实“图片 + TXT”作品文件夹。

## 验收状态

尚待实际操作确认：

- iOS 工程与自动化构建；
- Files/Lark 目录授权和持久访问；
- 文案复制、多图系统分享；
- 分享计数、次日回收和 7 天清理；
- AltStore IPA 打包与安装说明。

尚未实现、尚未验收：

- iPhone 同一 Wi-Fi 自动发现和前台接收；
- ZIP 自动传输和解压；
- TestFlight 签名、上传、链接安装及更新；
- 快捷指令 iCloud 正式安装链接。

没有实体 iPhone 测试记录前，不得宣称任何 iOS 功能测试通过。
