# 分享中控 V3.4 维护交接

更新时间：2026-07-20

## 当前可交付基线

| 项目 | 当前值 |
|---|---|
| Windows | 素材投送中控 V3.4，原生 Win32 x64 |
| Android | 相册 0.4.4，versionCode 21，`com.zwm.gallery` |
| iPhone | 相册 0.4.4，build 15，源码 bundle id `com.zwm.album` |
| 0.4.4 功能代码提交 | `8ac0aa186292ef54aa32397e40fc46db01b05fd9` |
| 主源码 | <https://github.com/zwmopen/team-video-workflow> |
| 构建备用仓库 | `rpgzwm/team-video-workflow-build`，只用于主账号额度不足时运行三端构建 |
| 安装包发布 | <https://github.com/zwmopen/gallery-updates/releases> |
| Android 更新索引 | <https://raw.githubusercontent.com/zwmopen/gallery-updates/main/latest.json> |

主源码仓库是唯一真源；`gallery-updates` 是公开安装和更新通道，不是源码仓库。备用构建仓库必须与主源码提交一致，不能形成第二条产品分支。

## 产品当前形态

- Windows：自动发现手机、拖放或选择文件/文件夹发送、真实进度与取消，同时接收手机文件到下载目录；
- Android：递归读取已授权目录、两列作品、复制 TXT、多图系统分享、分享次数、次日回收与 7 天保留、三端互传；
- iPhone：同包兼容 iOS 12+；较新系统可授权外部目录，旧系统使用 App Documents；作品、分享、回收与三端互传逻辑与 Android 对齐；
- 三端传送：可信同一 Wi-Fi 内自动发现，UDP 45834；HTTP 45833；任务创建、逐文件 SHA-256、提交或取消。
- Android 与 iPhone 设置中有独立的声音通知、震动提醒开关，默认均关闭；Android 前台接收服务的系统常驻提示使用独立静音通道。
- Android 点击更新后在 App 内下载 APK、显示进度并校验 SHA-256，不再打开网页；系统安装确认仍由 Android 负责。iPhone 更新检查不再跳网页，侧载包仍必须由电脑重新签名覆盖。
- iOS 12 设置页提供 App 内“选择作品文件夹”，从相册接收目录及其子目录选择扫描根目录；iOS 13+ 继续使用系统外部文件夹选择器。

## 代码地图

| 路径 | 职责 |
|---|---|
| `windows-native/` | Win32 UI、发现、发送、接收、日志和进度 |
| `android/app/src/main/java/com/zwm/gallery/` | SAF 目录、作品库、回收站、分享、发现与传送 |
| `ios/Album/` | UIKit/Swift 作品库、目录授权、分享、发现与传送 |
| `docs/PROTOCOL.md` | 局域网发现与 HTTP 传输协议 |
| `.github/workflows/device-share-hub.yml` | Windows、Android、iOS 三端构建与检查 |

## 文档路由

- 用户流程和功能边界：`README.md`
- 产品设计：`docs/ALBUM_WORKFLOW_DESIGN.md`
- 当前交接和版本：本文件
- Bug 与回归：`docs/BUG_LEDGER.md`
- 测试方法：`docs/TEST_PLAYBOOK.md`
- 兼容设备：`docs/COMPATIBILITY_AND_TEST_MATRIX.md`
- 源码、构建和恢复：`docs/SOURCE_BUILD_AND_RECOVERY.md`
- Windows + iPhone 侧载：`docs/IOS_WINDOWS_SIDELOAD_HANDOFF.md`
- V3.4 传送设计：`docs/V3.4_CROSS_DEVICE_TRANSFER.md`

## 2026-07-20 实际状态

- iPhone 6 / iOS 12.5.8 已覆盖安装 0.4.3 build 14，设备应用清单、最低系统版本和启动进程均已核对。
- 通过 App Documents 实体放入两层测试目录 `TestBundle/WorkOne`，其中含一个 PNG 扩展名文件和一个 TXT；首页数字显示 1、作品卡片显示 1，临时目录随后已从设备删除。
- 用户从另一台手机向 iPhone 6 发送后观察到内容能够收到，证明当前局域网接收方向可用；0.4.4 的 App 内文件夹选择入口需在本次构建安装后继续核对。
- iPhone 6 系统应用清单确认苹果官方“文件”App 缺失。它不是局域网接收和扫描的依赖；恢复官方 App 必须走 App Store，Windows 侧载工具不能替 App Store 静默安装。
- 0.4.4 功能提交为 `8ac0aa186292ef54aa32397e40fc46db01b05fd9`，备用仓库三端构建 run `29722256531` 已启动；最终结论和产物校验在构建完成后补入本节。
- 公开更新通道仍保持 0.4.1，0.4.4 的安装、核心入口和更新链路完成实体核对前不修改 `latest.json`。

## 2026-07-19 实际状态

- 0.4.3 候选包在备用构建 run `29694412271` 完成三端构建；iOS 模拟器测试实际执行并通过标准 Deflate ZIP 解压用例；
- 0.4.3 APK 与 IPA 已下载到本机桌面。iPhone 6 当晚已关机，导入入口、实体 ZIP 和扫描结果留到下次开机后检查，尚未创建正式 Release；
- 0.4.2 在备用构建仓库 Actions run `29693667340` 完成 Windows、Android 单元测试/编译/Lint、iPhone 测试目标/真机 SDK 编译及 IPA 结构校验；
- iPhone 6（iOS 12.5.8）已覆盖安装 0.4.2 build 13，系统查询确认最低版本仍为 iOS 12；升级后的偏好文件中两个新键均不存在，代码读取结果为默认关闭，且启动时未再申请通知权限；
- 当前未连接 Android 真机，因此 0.4.2 的安卓结论只包含云端自动检查，不沿用旧版实机结果替代本次开关操作。

- 最终三端工作流全部成功；Windows EXE、Android APK、iOS IPA 由同一源码提交构建；
- Android 0.4.1 与 iPhone 0.4.1 build 12 已覆盖安装，升级未清空目录授权和作品状态；
- Android 传送页同时显示 Windows 与 iPhone，20 秒后两台仍在；
- iPhone 最近设备记录同时包含 Windows 与 Android；Windows 同时发现两台手机并过滤自己；
- iPhone 前台保持 32 秒后 `/v2/info` 仍响应；任务创建、带 SHA-256 上传、取消分别返回 201/200/200；
- Windows → iPhone 已完成正式文件提交；iPhone → Android、iPhone → Windows、Android → Windows、Windows → Android 均有实体操作或接收日志；
- Android → iPhone 原 timeout 根因已修复并完成接收协议上传；为避免向用户作品目录写入额外测试文件，最后一次复核使用上传后取消。

## 后续优先观察

1. 在 iPhone 6 实体打开 0.4.3 的“导入文件或 ZIP”，分别检查多选图片+TXT、标准 Deflate ZIP 和递归作品数量；
2. 从 Android 系统文件选择器向 iPhone 正式发送一个业务目录，核对 iPhone 目标目录落点；
3. 在真实跨日使用中继续观察次日回收与进入回收站 7 天后的清理；
4. 用超大复杂文件夹观察 Windows 长时间进度、取消和剩余临时文件清理；
5. 修改手机名称后观察三端显示刷新；
6. iPhone 6 继续检查 iOS 12 固定 Documents 路径下的接收、回收和恢复。

这些是持续观察项，不代表当前版本不可用。发现问题时先补复现与证据，再修改公共兼容路径，禁止只为单台设备另做包。

## 发布与恢复最短路径

1. 从主仓库 `main` 或正式提交恢复源码；
2. 推送涉及本项目的代码，运行三端 GitHub Actions；额度不足时把同一提交同步到备用构建仓库；
3. 下载同一次运行的 EXE/APK/IPA，核对版本和 SHA-256；
4. Android 使用原 applicationId 与签名覆盖安装；iPhone 用 Sideloadly/AltStore 以用户自己的账号重新签名；
5. 把三端包发布到 `gallery-updates`，再更新 `latest.json`；
6. 通过公开 raw 地址确认线上版本，而不是只看本地提交；
7. 推送主源码和备用副本，确认工作区干净。
