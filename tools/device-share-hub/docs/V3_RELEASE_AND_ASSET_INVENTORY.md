# 分享中控 V3 发布与开发资产盘点

## 产品定位

“分享中控 V3”是同一个产品的三端组合，不是三套互不相干的项目：

- Windows 原生面板：发现手机、拖放 ZIP/文件夹/图片、显示传输进度和错误；
- Android“相册”0.3.13：同一 Wi-Fi 通用文件/文件夹接收、跨通道去重、作品识别、数量徽标、复制文案、系统分享、次数记录和回收站；
- iPhone“相册”0.2.0：同包兼容 iOS 12 及以上、递归识别作品、数量徽标、复制文案、系统分享、次数记录和真实目录回收站；
- iOS 快捷指令：轻量备选方案，动作设计已存档，正式 iCloud 安装链接仍待实体机搭建与验收。

产品原则：少一步就是价值。默认自动发现，不增加手动 IP、配对码、命令行、Node.js 网页面板、ADB、Root、越狱、无障碍或设备管理权限。

## 最短使用流程

### Windows + Android

1. 手机打开“相册”，与电脑连接同一 Wi-Fi。
2. 把一个 ZIP、单个作品文件夹或图片拖到 Windows 手机卡片。
3. 手机识别作品；点击作品后复制 TXT，并把全部图片交给系统分享面板。
4. 当天显示“已打开分享 N 次”；下一天进入回收站，保留 7 天。

### iPhone

1. 把解压后的作品放入“文件”中一个固定总文件夹；每个作品文件夹直接包含图片和 TXT。
2. 新 iPhone 首次选择一次总文件夹；iPhone 6 直接使用“我的 iPhone/相册”。0.2.0 会按“总文件夹 → 作品包 → 单个作品”递归寻找图片与 TXT，并忽略 `.Trash` 和“相册回收站”。
3. 点击作品，复制文案并打开 iOS 系统分享面板。
4. 免费 Apple ID 侧载有效期 7 天；轻量 `sideloadlydaemon` 约每 96 小时自动续签。

iOS 无法授权整台手机根目录。应选择实际作品目录、iCloud Drive 目录或“我的 iPhone/相册”文件共享目录，不能把“我的 iPhone”根节点当成 Android 文件系统根目录。

## 发布资产

| 资产 | 位置 | 用途 |
|---|---|---|
| 总说明 | `tools/device-share-hub/README.md` | 用户入口、功能和验收状态 |
| 设计说明 | `tools/device-share-hub/docs/ALBUM_WORKFLOW_DESIGN.md` | 产品规则、交互和安全边界 |
| V3 实现记录 | `tools/device-share-hub/docs/V3_IMPLEMENTATION_NOTES.md` | 三端开发与实体机验证记录 |
| Windows/iOS 侧载手册 | `tools/device-share-hub/docs/IOS_WINDOWS_SIDELOAD_HANDOFF.md` | 安装、代理、签名和设备排障 |
| iOS 使用说明 | `tools/device-share-hub/ios/README.md` | 目录结构、安装和构建 |
| iOS 变更记录 | `tools/device-share-hub/ios/CHANGELOG.md` | 版本差异和验收边界 |
| 快捷指令清单 | `tools/device-share-hub/ios/shortcut/README.md` | iOS 轻量方案搭建步骤 |
| Windows 源码 | `tools/device-share-hub/windows-native` | 原生面板 |
| Android 源码 | `tools/device-share-hub/android` | Android/HarmonyOS 客户端 |
| iOS 源码 | `tools/device-share-hub/ios/Album` | UIKit 客户端，同包兼容 iOS 12+ |
| CI | `.github/workflows/device-share-hub.yml` | 三端编译、测试、密钥扫描和 IPA 结构校验 |

GitHub Release 应保存版本化安装包、SHA-256 和脱敏发布说明。主仓库保存源码与设计；Android 公开更新仓库只保存公开 APK/版本清单，不保存源码、用户素材或诊断日志。

## 视觉与交互语言

- 名称统一为“相册”，图标使用“相册卡片 + 绿色投送箭头”，表达素材与传送，但不冒充系统照片 App。
- 主界面优先内容，不堆按钮；高频动作放主界面，设置、诊断和说明放设置页。
- 手机作品卡片两列布局，未分享保持清晰，已打开分享整卡降灰并显示次数。
- 使用浅色、柔和蓝绿、圆角卡片和系统原生控件；状态反馈短、明确、可行动。
- 电脑端以设备卡片、拖放区和真实进度为核心；错误提示必须告诉用户下一步做什么。
- 不使用技术术语要求用户理解端口、证书、协议或命令行。

## 本机与构建资产（脱敏）

- Windows 11 开发/交付机；
- Apple 官网版 iTunes、iCloud、Apple Mobile Device Support；
- Sideloadly 0.60.0，轻量 daemon 登录启动，约 96 小时刷新；
- AltServer 作为备选，已取消开机启动以避免与 Sideloadly 争用设备；
- GitHub Actions `macos-15` + Xcode 16.4 构建 iOS；
- Android JDK 17、Gradle 9.4.1、compile/target SDK 36；
- Windows 使用原生 Win32 C++，不依赖 Node.js、浏览器面板或 .NET 运行时。

本机绝对路径、Apple ID、密码、验证码、设备 UDID、配对记录和个人文件夹名称不属于可复用资产，禁止写入仓库或 Release。

## 复用经验与避坑

1. iOS 内部 `.app` 目录和可执行文件必须使用 ASCII；中文只放 `CFBundleDisplayName`。
2. CI 必须解包检查 IPA 路径、可执行文件、版本和显示名，不能只看 `xcodebuild` 成功。
3. iOS 文件选择器授权的是具体目录，不是手机根文件系统；`.Trash` 必须忽略。
4. 作品扫描应有限递归、遇到有效作品即停止向下、限制深度和总目录数，避免复杂目录拖垮界面。
5. 诊断顺序固定为 USB → iTunes → 侧载工具 → Apple 账号 → 签名安装 → App 功能。
6. 同时检查 `ALL_PROXY`、`HTTP_PROXY`、`HTTPS_PROXY`、系统代理和监听端口；只修当前进程，不擅改用户全局网络。
7. 免费侧载按 7 天设计，daemon 提前刷新；主界面不需常驻，电脑不必一直开。
8. Android/HarmonyOS 与 iOS 的目录权限模型不同，不能照搬路径假设；以系统文件选择器真实授权为准。
9. 分享次数只代表打开了系统分享面板，不代表平台发布成功。
10. 未经过实体手机实际操作的能力不得宣称测试通过，直接记录尚未操作的项目。

## 已知待办

- iPhone 0.2.0 需要分别在 iPhone 6 与较新 iPhone 检查固定目录/外部目录、分享和真实目录回收站；
- iOS 快捷指令正式 iCloud 链接尚未制作；
- iOS 跨自然日回收和 7 天清理尚未完成实体机跨日验收；
- Android 发现信息和更新检查均读取安装包的真实版本，不再保留历史硬编码版本字段；
- Windows/Android/iOS 每次发布继续执行三端 CI 和脱敏检查。
