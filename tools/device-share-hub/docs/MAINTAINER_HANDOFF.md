# 分享中控 V3.8 维护交接

## 2026-07-26 当前增量

- Windows V3.8：`windows-native/src/usb_transport.*` 实现 WPD/MTP 与 iPhone House Arrest 文件共享；`usb_bridge.py` 作为 EXE 资源内置。通道优先级 USB → Wi‑Fi。
- USB 只检测到充电/调试接口时，界面隐藏系统驱动名并提示把手机 USB 用途切换为“文件传输”；不把 ADB 作为正式传送依赖。
- 多手机同时在线时，未开放 MTP 的匿名 Android USB 接口保持独立卡片，不再靠“唯一在线安卓”猜测身份，防止关联到错误设备。
- Android 开放 MTP 后，以 USB 厂商和设备硬件标识把 WPD 与底层接口合并为同一张真实设备卡；不得退回按在线数量猜测。
- 传送历史：`%LOCALAPPDATA%\ZwmDeviceShareHub\transfer-history.tsv`，只在接收端提交/校验成功后写入；文件夹指纹包含相对路径、大小和逐文件 SHA-256。
- Android 0.5.7/code34：外部来源消失后二次确认并清除私有副本。K60 真机测试前备份位于 `D:\AICode\运行数据\device-share-hub\backups\k60-before-source-reconcile-20260726.tgz`。
- 真机证据：K60 外部 `Download/Lark` 为 24 个作品；正式 0.5.7 覆盖后首页 24，两个幽灵活动项和两个幽灵回收项已清除，目录授权保留。
- 互联网中继尚未部署；不得把同包名当成身份，也不得提前开放无后端的假开关。详细约束见 `docs/REMOTE_CHANNEL.md`。

更新时间：2026-07-26

## 当前可交付基线

| 项目 | 当前值 |
|---|---|
| Windows | 素材投送中控 V3.8，原生 Win32 x64 |
| Android | 相册 0.5.7，versionCode 34，`com.zwm.gallery` |
| iPhone | 相册 0.5.1，build 20，源码 bundle id `com.zwm.album` |
| 当前主要变化 | 来源一致性修复、成功传送内容指纹去重、Android/iPhone USB 通道与 USB → Wi‑Fi 自动回退 |
| 当前功能提交 | 发布后以本文件所在提交为准 |
| 主源码 | <https://github.com/zwmopen/team-video-workflow> |
| 构建备用仓库 | `rpgzwm/team-video-workflow-build`，只用于主账号额度不足时运行三端构建 |
| 安装包发布 | <https://github.com/zwmopen/gallery-updates/releases> |
| Android 更新索引 | <https://raw.githubusercontent.com/zwmopen/gallery-updates/main/latest.json> |

主源码仓库是唯一真源；`gallery-updates` 是公开安装和更新通道，不是源码仓库。备用构建仓库必须与主源码提交一致，不能形成第二条产品分支。

## 产品当前形态

- Windows：自动发现手机、拖放或选择文件/文件夹发送、真实进度与取消，同时接收手机文件到下载目录；
- Android：递归读取已授权目录、两列作品、点击即记分享次数、笔记/文件双模式、可配置小时级清理、三端互传；
- iPhone：同包兼容 iOS 12+；较新系统可授权外部目录，旧系统使用 App Documents；作品、分享、回收与三端互传逻辑与 Android 对齐；
- 三端传送：可信同一 Wi-Fi 内自动发现，UDP 45834；HTTP 45833；任务创建、逐文件 SHA-256、提交或取消。
- Android 与 iPhone 设置中有独立的声音通知、震动提醒开关，默认均关闭；Android 前台接收服务的系统常驻提示使用独立静音通道。
- Android 点击更新后交给系统下载管理器，不打开网页，也不申请“安装其他应用”权限；系统通知负责进度和安装入口，下载完成后 App 后台核对 SHA-256。iPhone 更新检查不再跳网页，侧载包仍必须由电脑重新签名覆盖。
- iOS 12 设置页提供 App 内“选择作品文件夹”，从相册接收目录及其子目录选择扫描根目录；iOS 13+ 继续使用系统外部文件夹选择器。
- Android 与 iPhone 只把最近一次本机扫描的作品数量作为设备状态公布；不公布作品名称、文案、图片或路径。旧客户端缺少该字段时保持兼容并显示为未知。

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

## 2026-07-26 实际状态

### Android 0.5.6 / iPhone 0.5.1

- Android 不再从首页启动独立文件浏览页。顶部“模式、传送、刷新、回收站、设置”冻结不动，模式按钮只替换下方作品网格或文件列表；文件夹内返回先退一级，根目录返回切回作品。五个入口都有短文字反馈。
- Android 文件列表使用文件夹、图片、文本、PDF、压缩包、视频、音频与普通文件独立图标；ZIP 是文档加拉链。顶部标题、数量、圆形按钮和间距针对 360dp 实机重新平衡。
- Android 与 iPhone 都把旧自然日记录迁移到北京时间精确毫秒：昨天及更早按当前规则到期；当天从升级时刻起保留完整 1 小时。iPhone 无状态旧回收站同样从升级时刻保留 1 小时。
- iPhone 自动移入回收站和彻底删除分别支持 1～10 小时，默认均为 1；前台每分钟维护，重新打开立即维护。版本为 0.5.1/build 20，继续保持 iOS 12 最低版本和原状态文件兼容。
- VIVO Y36t 实机覆盖前为 0.5.4/code 31、13 个作品、15 个回收站、根目录 `Download/Lark`。0.5.6 迁移后仍为 13 个作品，回收站和真实 `相册回收站` 均为 0，设备名与目录授权保留；同日 1 次分享作品没有被首次启动立即删除。
- 同一 VIVO 已覆盖正式 Release 0.5.6/code 33；`run-as` 报告包不可调试。单元测试、Release 编译与 Release Lint 成功。实机模式切换保持 `MainActivity` 不变，文件根目录显示 15 项，返回后恢复 13 个作品。
- iPhone 连接设备为 iPhone13,2 / iOS 26.5.2；覆盖安装前实际版本为 0.4.7/build 18，最终侧载 bundle id 为 `com.zwm.album.TXA6HP98BX`。0.5.1/build 20 已沿用该最终标识通过 Sideloadly 覆盖安装并启动；安装后仍显示 10 个作品，原“已打开分享 2 次”状态仍在。禁止卸载绕过，以免丢失 bookmark 与本机状态。
- 备用构建 run [`30184472766`](https://github.com/rpgzwm/team-video-workflow-build/actions/runs/30184472766) 的 Windows、Android、iPhone 三端任务全部成功；iPhone 11 项测试全部通过，并完成真机 SDK 编译和 IPA 结构检查。
- 正式安装包已发布到 [`gallery-updates v0.5.6`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.5.6)：Android APK SHA-256 `411905D7DCC655184F00183EF0E2C2F3C9DAA39D0B1B5C38652F22D935E91FDA`，iPhone IPA `99BB23E86A4397217C387F45A69449DCCE8FCB9E37DC2FDDF78F3F9D00435BE5`，Windows EXE `8EE011841995167A23BEF80C1E11E32F12C3A8D2555E071A512DD52A7BDFE1FC`。Android 在线更新索引已指向 0.5.6/code 33，并包含同一 APK 哈希。
- Redmi 本轮尚未被 Windows 的 USB 调试接口枚举到，因此没有把“在线可见”误写成“已覆盖安装”；连接后应使用同包覆盖并先后核对版本、作品数、目录授权和回收站。
- 本轮未替用户进入任何内容平台或执行真实发布；界面、文件扫描、迁移和覆盖安装证据与外部平台发布结果分开记录。

### 2026-07-26 Android 0.5.5

- 连接设备为 Redmi Note 8 / Android 10，应用内名称 `Redmi Note 8（A2）`；升级前安装 0.5.0/code 27、当前作品 16、回收站 19、根目录 Lark。覆盖升级前的应用私有数据已保存到运行数据目录，仓库不保存用户作品。
- 诊断确认旧版 21 次历史分享流程依赖系统目标回调才记账；当前 16 个作品的分享字段全部为空。内容哈希另确认 2 个当前项与回收站项完全相同、2 个当前作品内部包含重复图片，这两条根因共同造成“发过的作品又作为未分享出现”。
- 0.5.5 把记账移到用户点击边界，ShareActivity 只在首次创建时执行一次；目标回调、取消和分身冷启动不再决定次数。已发起过的作品再次分享前显示确认。
- 作品库为新旧条目生成“文案 + 去重图片内容”指纹。只有当前项与回收站项内容完全一致时合并并以回收状态为准；旧库的应用私有重复图片同步去重，用户选择的原目录不被修改。
- 首页增加“小红书笔记 / 文件浏览”切换。文件模式直接浏览已授权 SAF 树，文件夹优先，可进入子目录并交给系统打开普通文件。
- 默认首次分享 1 小时后进入回收站，同时在 1 小时边界从文件管理中彻底删除；设置支持分别填写 1～10 小时，删除时间不得早于回收时间。分享 MediaStore 副本、接收暂存和传送压缩缓存均满 1 小时清理。
- 升级安全：新策略依赖 `firstSharedAtMs`，旧记录没有该字段时不追溯自动删除；包名、签名、目录授权、名称、现有次数与回收站结构保持兼容。
- 自动检查：新增点击时间、精确 1 小时边界、无时间戳旧记录、跨 active/trash 内容指纹及重复图片测试；本地 Gradle 9.4.1 单元测试与 Debug 构建成功。
- 真机检查：同签名 Debug 覆盖为 0.5.5/code 32，名称和 Lark 授权保留；作品 16→14，回收站保持 19，内部重复图片哈希组 2→0。文件模式实读 Lark 为 20 项。临时私有自检作品点击一次即 `shareCount=1`；把测试时间模拟到 1 小时后，测试作品、分享缓存记录和媒体缓存均被清理，真实数据回到 14/19。
- 未替用户选择小红书目标或执行真实发布；不同手机的厂商文件管理删除表现按用户后续连接设备逐台复核，同一正式包继续兼容，不做机型分叉。
- 功能提交为 `d1d55b8`，已同步主仓库与备用构建仓库。主账号 Actions run `30182688637` 在任何步骤开始前因账号运行资源问题结束；备用账号 run [`30182693533`](https://github.com/rpgzwm/team-video-workflow-build/actions/runs/30182693533) 对同一提交完成 Windows、Android、iPhone 三端构建，三个任务全部成功。
- Android 公开正式版为 [v0.5.5](https://github.com/zwmopen/gallery-updates/releases/tag/v0.5.5)，APK SHA-256 为 `AF8E016B16029047C654BB6805FEBAACC181C407A94D6C4BB8023F79CDDBDD02`。`latest.json` 已同步 0.5.5/code 32；从公开下载地址重新下载后的哈希与索引、实机安装包一致。桌面保存 `相册-Android-0.5.5.apk`。

- Android 0.5.4 修复 HarmonyOS / EMUI 系统选择器可能先返回 Activity 结果、后送达所选目标回调的竞态。Activity 结果先到时保留 1.2 秒回调窗口；晚到回调仍记为“已打开分享”，没有目标回调才按取消结束。VIVO 的快速返回与 20 秒分身冷启动观察保持原路径。
- 新增 4 项纯 Java 分享结果时序回归，覆盖“结果先到、回调后到”“回调缺失”“正常快速返回”“正常长时间返回”；Android 全量单元测试共 37 项，Release 编译与 Release Lint 均成功。
- 作品卡片高度由 190dp 收紧到 174dp，水平间距与内边距同步收紧；默认卡片使用 1dp 柔和阴影、暖灰细描边和 16dp 圆角，减少四角厚重感。操作按钮保持 44dp 高，顶部四入口仍保持 48dp 点击区域。
- 设置中的“软件说明”改为正式产品介绍，覆盖作品工作流、普通文件传送与预览、三端互传、本机状态和设计思路。
- 主源码和备用仓库已同步功能提交 `4431e0f`；备用 run `30018017715` 的 Windows、Android、iPhone 三端任务全部成功。
- 云端正式 APK 为 0.5.4/code 31，SHA-256 `AE385DA1C22AFEDBECAF510CA0B5B93764F1271CCBB37292C93A3A5D5FC47186`；v2 签名证书与旧版一致，正式清单没有 Debug 标志和 `REQUEST_INSTALL_PACKAGES`。公开 Release、更新索引与桌面 APK 已同步，重新在线下载的 APK 哈希与索引一致。
- 局域网实读 Huawei P30 / Android 10 当前仍为 0.5.2，作品数 27、接收在线；电脑没有 USB/ADB 枚举，因此 0.5.4 尚未覆盖到该机，实体分享计数和卡片观感需安装后操作复核。此状态不影响本机自动回归结论。

- Android 0.5.3 针对 0.5.2 Release 包仍出现厂商风险提示继续做单变量隔离：删除 `REQUEST_INSTALL_PACKAGES` 和 App 直接安装器调用，改为 Android 系统下载、系统完成通知和系统安装确认；包名、签名和数据结构不变。
- 本机已完成 33 项单元测试、Release 编译与 Release Lint；备用 run `30010535186` 的 Windows、Android、iPhone 三端任务全部成功，Android 新门禁实际确认正式 APK 不含安装包请求权限和 Debug 标志。
- 云端正式 APK 为 0.5.3/code 30，SHA-256 `84484D02E47FD5EC96283D03DA7DB54BF3DFFADE365C8D0008D8DFC44C4A277D`，v2 签名证书与旧版一致。公开 Release `v0.5.3`、源码 ZIP、哈希和在线索引均已发布，重新在线下载的 APK 哈希与索引一致；桌面也保存同一文件。
- 当前 `adb devices` 没有枚举到 Android 手机，因此这一版的覆盖升级、系统下载通知和厂商风险提示结果需要设备重新连接后复核；此处只记录已完成的本机、云端和在线交付检查。

- Android 0.5.2 根据 0.5.1 实体视觉反馈修正顶部布局：作品数字紧跟标题，四个入口为真正圆形、48dp 点击范围和统一间距。备用 run `29980259163` 三端成功；云端 Release APK SHA-256 `E32ADC584AE17CBF0B1797E87528F6BE804E41046C631216338A05039DD953E0`，签名与旧版一致且不含调试标志。
- 公开 Release `v0.5.2`、源码 ZIP、哈希和在线更新索引已发布；线上索引实读为 0.5.2/code 29，重新下载 APK 的哈希一致。当前 USB 未枚举到设备，修改后的实体截图与点击手感需手机重新连接后复核。

- Android 0.5.1 修复 0.5.0 误发 Debug APK：CI 改为测试、构建和上传同签名 Release APK，`debuggable` 关闭；扫描、分享、分身、传送、回收、更新和诊断代码没有依赖 Debug 开关。
- Android 所有页面增加 Android 15+ 系统栏与水滴/刘海安全区适配；首页四个顶部入口扩大为 48dp，并调整窄屏标题与间距。
- 本机 Gradle 9.4.1 已完成 `testDebugUnitTest`、`assembleRelease`、`lintRelease`；0.5.1/code 28 APK v2 签名通过，证书与 0.5.0 相同，本机构建 SHA-256 `83D2934402C3483103D0A47101545EA4424A2482F74F954844B4044511109C56`，清单没有 `debuggable=true`。
- 备用构建 run `29979168314` 对提交 `9d741b6` 完成 Windows、Android、iPhone 三端检查；云端正式 APK SHA-256 `8E8EB717C0738624677C96CBC3EFBB17D5AEF30AF30401AC21E0EA324CB89E8C`，签名证书与旧版一致且清单没有 `debuggable=true`。桌面与公开发布使用该云端产物。
- 主源码与备用构建仓库已同步；公开 Release `v0.5.1` 已提供 APK、源码 ZIP 和 SHA-256。线上 `latest.json` 实读为 0.5.1/code 28，重新下载 APK 的哈希与索引一致。
- 当前没有连接 Android 实体设备，因此覆盖升级数据、水滴屏点击手感和厂商安全提示仍需下次连接真机复核；公开发布和自动检查不能替代这三项实体操作。

- 2026-07-23：Android 0.5.0 在 Redmi 9A 与 VIVO 上同签名覆盖安装，作品详情、图片长按多选和三图标操作完成实体操作确认；作品原目录里的 TXT、JSON 等非图片附件实际显示类型、名称和大小。升级没有清空目录授权、作品库或回收站。
- VIVO 分身冷启动实测出现分享目标约 2.9 秒提前返回，等待后诊断记录 `outcome=deferred_target_opened`；只有真正打开目标才计数，测试产生的作品分享字段已恢复。
- Android 本地使用 Gradle 9.4.1 完成单元测试、Debug APK、Lint；iPhone 采用 ImageIO 降采样，图片回收站按日期保留 7 天。
- Windows V3.7 替换了损坏的旧 ICO，直接使用 iOS/手机端同源绿色相册图标；设备卡显示平台标识、圆角按钮和更宽松的底部状态布局。
- 备用构建 run `29968611397` 对提交 `c5393db` 完成三端检查：Windows 正式编译、Android 单元测试/Debug APK/Lint、iPhone 测试/真机 SDK 编译和 IPA 结构校验全部成功。
- 正式产物：Android 0.5.0/code 27 SHA-256 `AB04978E91206BA72737766C7E92F52121F6836E772974DE35B720B258A1C12D`；iPhone 0.5.0/build 19/最低 iOS 12.0 SHA-256 `A94D517EFC9FF0242719800BA3AFE08A5732E5D6C4C8798C7147DF81E4487A23`；Windows V3.7 SHA-256 `5FE8ACA9882BBDDD0F85EF54335BF0C1CBFAA027F9540C1A2B7B6CBBE329110B`。
- `gallery-updates` 已发布三端 0.5.0/V3.7 和同提交源码归档；Android 更新索引指向 0.5.0/code 27 的直接 APK 与对应哈希。
- 云端 APK 已再次覆盖安装到 VIVO 与 Redmi 9A；VIVO 实体详情重新读到“其他文件”和 TXT，设置页返回“已是最新”，证明发布包、附件预览和在线索引闭环一致。
- Windows V3.7 已替换桌面运行中的 V3.6；窗口标题为 V3.7、进程可响应，TCP 45833 与 UDP 45834 均正常监听，桌面快捷方式指向新版 EXE。

- Redmi 9A / Android 11 / MIUI 12.5 的互相发现故障已定位为 Wi-Fi 短暂变化时 UDP 发现线程因 `ENETUNREACH` 永久退出；HTTP 接收线程一直正常，因此不能把“端口可访问”误判成“发现正常”。
- Android 0.4.9 把发现 socket 改为可恢复会话。实体覆盖安装后版本为 0.4.9/code 26，原目录授权、1 个作品、接收开关和设备名称保留；应用进程不重启完成 Wi-Fi 断开/恢复后，电脑重新发现手机，手机同时看到 Windows 和 iPhone，等待超过 15 秒仍在线。
- 本地发布构建已执行 Android 单元测试、Debug APK 编译和 Lint；发现恢复新增两条单元测试，分别覆盖临时网络错误重试与正常停止不重试。

- 2026-07-21 整理 `D:\AICode` 根目录时确认：旧目录 `素材投送中控` 仅含早期 Python MVP 与说明，不是当前 V3.6 三端真源；已可恢复地隔离到运行数据，正式源码、构建和发布仍只认本仓库。

- Android 0.4.8 把 0.4.7 的长按单选升级为复选框批量选择：所有卡片显示勾选框、顶部显示选中数量，右下角垃圾桶逐项移动并报告部分失败。红米 K60 实体连续选中两项时顶部正确显示“已选 2 个”，随后按返回取消，没有执行删除。
- 0.4.7 / Windows V3.6 已完成源码升级：Android 10+ 使用系统 MediaStore 临时分享区解决厂商分身跨空间读取私有 URI 不稳定；Android 8/9 保留旧通道。临时副本只记录系统 `media` URI，并在下一自然日清理。
- Android 顶部工具栏改为冻结布局；长按作品单选后右下角显示垃圾桶，确认后移动真实来源和私有副本到现有回收站，保留分享次数，并对外部移动失败做回滚保护。
- 设备名称不改写，只在 `workCount` 已知时追加“（作品数 N）”；Windows、Android、iPhone 与共享技能规则一致。
- 备用构建 run `29732591472` 对提交 `9b01b53` 完成三端检查：Android 单元测试、编译与 Lint，Windows 正式编译，iPhone 测试与 iOS 12+ 真机 SDK 编译全部成功。
- 红米 K60 已沿同一签名从 0.4.1 覆盖到 0.4.7，再覆盖到 0.4.8；升级前后均为首页 11 个作品、回收站 2 项，应用私有作品库文件哈希一致，目录授权、分享次数和回收站状态没有被重置。
- 红米 K60 的小米分身小红书已由 0.4.7 的 `media_store` 通道实际打开到分身用户 `u999` 的图片编辑页，10 张缩略图完整加载；随后返回，未执行发布或保存草稿。诊断记录含 `share_sheet_launch images=10 strategy=media_store`、`share_opened` 和 `share_finished`。
- 红米 K60 已实际向下滚动作品列表，顶部工具栏保持固定；0.4.8 长按后全部卡片显示复选框，连续勾选两项显示“已选 2 个”，按返回安全取消，首页仍为 11、回收站仍为 2。
- Android 0.4.8 APK SHA-256 为 `4619BED86303AE020268D29E6190BEBCB7E7365598E2D5D2C74735E5F29C98FB`；签名证书 SHA-256 与旧版一致。Windows V3.6 SHA-256 为 `1C8F044F7567ABAC32E5EE1F5E68F0226C6A329A9FF5DD4F0A9C147F2C3A062D`；iPhone 0.4.7/build 18/最低 iOS 12.0 SHA-256 为 `8D916CC42CF323EF262709DE53353577A5ED3B66D63AE782B56CE659B771370E`。
- `gallery-updates` 已发布 Android 0.4.8、iPhone 0.4.7 和 Windows V3.6；公开 raw 更新索引实读为 versionName 0.4.8、versionCode 25，并与 APK SHA-256 一致。Android 当前版本页已显示 0.4.8；发现更新时由 App 内直接下载、显示进度、校验并调起系统安装器，不跳发布网页。
- Windows V3.6 已放到桌面并运行，桌面快捷方式已指向 V3.6，TCP 45833 由该进程监听。
- iPhone 12（iPhone13,2 / iOS 26.5）已用原有 Apple ID 和原最终 bundle id `com.zwm.album.TXA6HP98BX` 从 0.4.1 覆盖到 0.4.7 build 18。安装后 `/v2/info` 返回 23 个作品，证明原作品库继续可读；电脑和共享技能同时发现“Xiaomi 主机（作品数 11）”与“苹果12（作品数 23）”，Windows V3.6 状态显示已发现 2 台设备。
- 本次 Sideloadly 在旧相册仍占前台时长期停在 `Installing 0%`，但没有签名或密码错误。使用已安装的 `pymobiledevice3` 挂载开发镜像并只发送一次系统 Home 键后，旧相册退出，安装立即完成到 100%。以后覆盖安装前先让目标 App 回到桌面并保持设备解锁；不要因 0% 停滞而卸载 App。
- 最终回归连续 30 秒、每 10 秒重新发现一次，两台手机始终同时在线并分别显示 11 / 23 个作品；Windows V3.6 手动刷新后连续观察仍显示“已发现 2 台设备”。
- 两台手机分别完成无业务文件的任务创建与取消，以及 30 字节探针的上传、SHA-256 校验和取消：HTTP 依次为 201 / 200 / 200，取消后均回到 `online`、`taskId` 为空。探针没有提交，Android 仍为 11 个作品、2 项回收站，iPhone 仍上报 23 个作品。
- 从红米 K60 拉取的实际安装 `base.apk` 与桌面及公开发布 APK 完全同哈希：`4619BED86303AE020268D29E6190BEBCB7E7365598E2D5D2C74735E5F29C98FB`。公开 APK、IPA、EXE 下载地址均返回 HTTP 200，Content-Length 分别为 972,823、16,781,861、581,632 字节。
- 共享技能作品数解析测试 2/2 通过；主源码脱敏扫描未发现 Apple ID、设备 UDID、旧容器标识、用户目录或指定素材路径。公开更新仓库的 Git 跟踪内容只有说明和 `latest.json`，安装包仅作为 Release 资产发布。
- 视觉后续项：冻结工具栏继续保留，但顶部与系统状态栏之间增加自然安全间距和触控缓冲；不得再次把工具按钮贴到屏幕顶边。
- 0.4.6 / Windows V3.5 增加设备作品数：发现包追加可选 `workCount`，手机 `/v2/info` 同步返回；Windows 卡片、Android/iPhone 互传列表和共享技能均消费同一字段。
- 备用构建 run `29727724327` 对提交 `9541d2b` 完成三端构建：Windows 正式编译、Android 单元测试/编译/Lint、iPhone 模拟器测试和 iOS 12+ 真机 SDK 编译全部成功。iOS 新增的旧包兼容与作品数解析测试实际执行通过。
- 产物核对：Windows V3.5 SHA-256 `366BF09A5D6B680EE9084477AB153191D8DEA37FA48B4591E86847DFF6CBD371`；Android 0.4.6/versionCode 23 SHA-256 `B125902F4C5996EF7361A6878565018393CB79B261761B35048A51BDFC5BAB94`；iPhone 0.4.6/build 17/最低 iOS 12.0 SHA-256 `23A765CFB15B03B7E712202B8D2926419A885EF1456AA5189BDE5648400C49F6`。
- 0.4.6 三端安装包已发布到 `gallery-updates`，Android `latest.json` 已在线更新到 versionCode 23。Windows V3.5 已放到桌面并启动，TCP 45833 接收端口由该进程监听。
- 当前在线小米仍是旧客户端，因此共享技能如实返回“作品未知”。手机覆盖安装 0.4.6 并完成一次扫描后，再核对实体数字与手机首页一致；不把云端构建代替这一步。
- 三端 0.4.4 云端构建 run `29722548994` 全部成功；IPA 内核对版本 0.4.4/build 15、最低 iOS 12.0，随后已覆盖安装到 iPhone 6 / iOS 12.5.8 并正常启动。
- 实体启动后首页显示 4 个作品，但其中两项来自系统重名目录“相册回收站 (1)/(2)”。根因是扫描器只排除精确回收站名；0.4.5 已同时修正 Android 与 iPhone，并加入数字重名副本回归用例。
- 0.4.5 三端构建 run `29723234708` 以提交 `23c2c07` 完成：Windows 编译、Android 单元测试/编译/Lint、iOS 测试目标/真机 SDK 编译和 IPA 结构校验全部成功。
- 0.4.5 build 16 已覆盖安装到 iPhone 6。相同实体目录在 0.4.4 显示 4 个作品，升级后显示 0；临时放入一个“图片 + TXT”作品时显示 1，放入第二个时显示 2，删除测试目录后恢复 0，确认递归识别与重名回收站排除同时生效。测试目录随后已从手机移除，原有回收站和用户文件未删除。
- iOS 12 的 App 内文件夹选择器由自动测试覆盖目录枚举、隐藏/回收站排除和路径保存逻辑；本轮 Windows 端没有可兼容 iOS 12 的触控自动化组件，因此没有用脚本代替用户在设置页点击该行。
- 用户指定的实体大目录样本已整体传入 iPhone 6：电脑与手机均核对为 129 个文件、263,885,655 字节，App 刷新后识别 14 个作品，与电脑一级作品目录数量一致。公开文档不记录用户本地目录名。
- 约 264 MB 的单 ZIP 经 Wi-Fi 上传到 50% 时，因为 iPhone 6 退到桌面、前台接收服务停止而断开；自动取消未留下半包。随后使用 USB 文件共享隐藏暂存、逐文件/字节核验和原子改名完成交付。此边界已沉淀到共享技能 `device-folder-transfer`。
- 共享技能源位于 `D:\AICode\AI\skills\技能包\技能\device-folder-transfer`，并以目录联接提供给 Codex、共享 Agents、Trae、Hermes 与 OpenClaw。已实测发现两台手机、计划核对大目录，以及发送 34 字节测试文件到 iPhone 后提交成功；测试文件随后从手机删除。

- iPhone 6 / iOS 12.5.8 已覆盖安装 0.4.3 build 14，设备应用清单、最低系统版本和启动进程均已核对。
- 通过 App Documents 实体放入两层测试目录 `TestBundle/WorkOne`，其中含一个 PNG 扩展名文件和一个 TXT；首页数字显示 1、作品卡片显示 1，临时目录随后已从设备删除。
- 用户从另一台手机向 iPhone 6 发送后观察到内容能够收到，证明当前局域网接收方向可用；0.4.4 的 App 内文件夹选择入口需在本次构建安装后继续核对。
- iPhone 6 系统应用清单确认苹果官方“文件”App 缺失。它不是局域网接收和扫描的依赖；恢复官方 App 必须走 App Store，Windows 侧载工具不能替 App Store 静默安装。
- 0.4.4 功能提交为 `8ac0aa186292ef54aa32397e40fc46db01b05fd9`；首次构建 `29722256531` 因工作流硬编码的旧版 Info.plist 断言失败，修正元数据后 `29722548994` 三端全部成功。
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
