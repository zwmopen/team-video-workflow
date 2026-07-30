# 分享中控 V4.1.1 维护交接

## 2026-07-30 Android 0.6.1 可拖动缩放悬浮剪切板

- 0.6.1/code 39 移除 Android 首页左下角重复入口，只保留系统悬浮圆点；圆点改为全屏双轴拖动，首次默认位于屏幕高度约四分之一处。
- 圆点点击后直接显示带描边的系统悬浮剪切板，标题栏拖动窗口，右下角手柄调整宽高；圆点和窗口均持久保存位置，窗口同时保存尺寸。
- 悬浮面板改为“顶部最新剪切一条、下方固定常用语、右上角新增”；每次点开圆点会读取当前系统剪切内容。同步快照只包含最新一条剪切内容与全部固定常用语/删除墓碑，接收端按发送设备清除旧的同步剪切项，因此不会把整套历史灌给其他手机；在线设备即时收到，重新上线设备由发现流程补齐。
- 常用语初始化自电脑端权威 `01-正式SOP/01-前端私聊承接与拉群SOP.md` 的 8 条“推荐回复”。使用稳定 ID 且只补不存在的记录，因此用户编辑和删除产生的新版本/墓碑不会被下次启动恢复覆盖。
- 本机 Gradle 9.4.1 下单元测试、Debug/Release 构建与 Release Lint 已通过；正式 APK 为 versionCode 39/versionName 0.6.1，SHA-256 为 `96EA7FF25C164B6314CE3F59A62B68B98752257E2ADFDBD1AF664C465B64788F`，清单不含 `REQUEST_INSTALL_PACKAGES` 和 Debug 标志。
- 当前 ADB 未连接设备；系统悬浮窗授权、跨应用点击复制、横竖屏拖动边界和不同厂商拉伸手势仍须在下一台实体手机上补验，不能把本地构建结果写成真机通过。

## 2026-07-29 Android 0.6.0 共享剪切板与系统分享

- 0.6.0/code 38 新增 Android 系统分享目标、共享剪切板/常用语、默认悬浮入口、截图主设备提醒和四分类数量。
- V2 任务新增可选 `autoShare`，缺省只落盘。普通文件、文件夹和截图按真实接收目录存放；仅系统分享进来的图片＋文字进入分享准备。平铺接收内容会整理进 `接收-taskId` 文件夹再扫描，保持外部文件夹为真源。
- 剪切板同步只用于当前局域网已发现并持久登记的手机；以来源 IP 加设备 ID 做便利校验，不宣称远程级加密认证。手机版继续不含坚果云。
- 功能源码提交为 `936f9ce`。本机 Gradle 9.4.1 下 51 个单元测试、Debug/Release 构建、Release Lint、APK 签名与清单校验通过；签名证书 SHA-256 仍为 `CAC54653FDFDBD19E0D9952FECA70E9B2A0530CE6676EB17A3EC24A56BE1848B`，可覆盖 0.5.10。
- 公开 Release 为 [`gallery-updates v0.6.0`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.0)，APK SHA-256 为 `84C53FF17FD3A74B1464C3857BF12D8C7833EBF8FD4C3085AA3977EBD711869D`；更新索引提交 `f6759d7` 已指向 0.6.0/code 38。公网重新下载 APK 的大小 777046 字节，哈希与索引完全一致。
- 主仓库 Actions run `30456810103` 的四个 job 均在执行任何步骤前被 GitHub 拒绝启动，注解明确为账户付款失败或 Actions spending limit；这是仓库计费状态，不是构建失败。本机 Android 全门禁结果与公网包校验作为本次发布证据，恢复额度后应补跑同一提交。
- 当前 ADB 无设备，不能把悬浮窗授权、截图权限、系统分享和多手机剪切板同步写成真机通过；设备接入后需按兼容矩阵逐台覆盖安装，不得跳过真实文件夹删除/清空回收站回归。

## 2026-07-29 Android 0.5.10 更新文件名修复

- 用户反馈 0.5.9 下载后的更新文件可能没有 `.apk` 后缀。根因是系统 `DownloadManager` 请求只设置了展示标题和 MIME，没有明确设置落盘文件名；GitHub 重定向后的厂商下载实现可能不保留源文件扩展名。
- 0.5.10/code 37 明确保存到系统下载目录，文件名固定为 `相册-Android-0.5.10.apk`；新增回归测试覆盖正常版本、空版本和含空格版本，保留启动自动检查、自动下载、SHA-256 校验与系统安装确认。
- 功能提交为 `9c39b68`，备用构建 run `30416349557` 的 Android 单元测试、Release 构建、Release Lint、安装权限门禁全部成功；同 run 的 Windows 和远程中继检查也成功。
- 公开 Release 为 [`gallery-updates v0.5.10`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.5.10)，APK SHA-256 为 `47EB541086AA1A4D8BE32FEEDF2860AA11A4590A2F4046919898D4313C14252D`；`latest.json` 已同步 0.5.10/code 37 并使用无查询参数的直接 APK 地址，帮助仍运行 0.5.9 的旧下载器保留文件名。
- 公网重新下载哈希与索引一致，最终响应明确返回 `Content-Disposition: attachment; filename=album-Android-v0.5.10.apk` 和 APK MIME。准备做 0.5.9 → 0.5.10 自动下载真机检查时设备已断开，实际系统下载目录中的中文目标文件名需下次设备连接后补验。

## 2026-07-29 Android 0.5.9 正式发布

- Android 0.5.9/code 36 的功能源码提交为 `5c881f2`；公开 Release 为 [`gallery-updates v0.5.9`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.5.9)，APK SHA-256 为 `7F5AA4FA66CACDC398A2911AF63C83CFCF394B55A1F5F4071073BAE8D0B91395`，并同时发布提交对应的源码 ZIP 与 `SHA256SUMS.txt`。
- `latest.json` 已同步到 0.5.9/code 36。重新从公开地址下载的 APK 哈希与索引一致；签名证书 SHA-256 为 `CAC54653FDFDBD19E0D9952FECA70E9B2A0530CE6676EB17A3EC24A56BE1848B`，与 0.5.8 一致，正式清单无 Debug 标志。
- 备用构建 run `30412045382` 中 Android 单元测试、Release 构建、Release Lint、安装权限门禁均成功，Windows 与 iPhone 任务也成功；整体 run 仅因未启用的远程中继在 Wrangler 4.114.0 本地 HTTP 冒烟末段触发 request-stream 运行时错误而失败，此错误不属于 Android 0.5.9 发布包。
- 公开 APK 已在当时连接的 VIVO `V2327A` 上覆盖安装成功，原 4 个作品仍可读取，四分类入口正常，页面显示“已是最新”，回收站物理目录为空；该次属于公开包安装与首页冒烟，不替代后续按 1—9 项对该机型进行完整验收。
- 华为 P30 `ELE-AL00` 已在发布前完成 0.5.9 正式候选包覆盖安装、目录读取、分类与回收站真机验收；针对鸿蒙 `.hwbk` 隐藏侧文件和 DocumentsProvider 过期条目的清空逻辑已验证真实目录为空。

## 2026-07-27 Windows 设备发现回归修复

- 用户实机反馈 V4.1.0 右侧一度显示 0 台，而同一时段至少 3 台在线。诊断日志确认 V4.1.0 启动后实际发现 `192.168.0.102/.104/.107` 三台；设备随后因 15 秒广播过期被移除，用户点刷新又触发 `gDevices.clear()`，导致列表长期为空。
- V4.1.1 不再在手动刷新时清空已知设备，只触发 UDP/USB 重新探测；局域网保留窗口由 15 秒改为 10 分钟，以适应手机系统后台节流。
- 界面比例由素材 61% / 设备 39% 改为素材约 38% / 设备约 62%；拖放仍只以右侧设备卡片为明确目标，原 `WM_DROPFILES` 和设备命中逻辑未删除。
- `device-remarks.tsv`、`device-channels.tsv`、`content-history.db` 的路径和格式均未改变；备注、作品数、USB/WiFi/远程权限和重复传送记录不得因界面升级重置。
- V4.1.1 CI、桌面包和三台实机可见性证据待构建完成后补写。

## 2026-07-27 Windows 单窗口工作台

- Windows V4.1.0 将 V4.0.0 的独立素材库窗口完全合并回主窗口：左侧素材与归档，右侧设备与临时文件传送，底部是全局进度、取消和诊断。
- 唯一主操作是“传送选中素材”。目录选择和归档危险确认仍使用系统对话框；日常素材选择、设备选择、刷新和传送不再跳出第二个应用窗口。
- 传输与归档实现没有复制分叉：素材双击和主按钮仍调用 `UploadToDevice`，归档仍执行 ZIP 结构/数量/SHA-256 校验后再移入回收站。
- 本版最小窗口为 980×640，默认 1120×720；后续界面调整不得恢复独立素材库窗口，也不得把设置拆成多个日常弹窗。
- CI、候选包、本机界面与真实操作证据待本次构建完成后补写；V4.0.0 EXE 保留作为可回退版本。

## 2026-07-27 Windows 素材库、SQLite 与安全归档

- Windows V4.0.0 在原三端传输中控上增加“素材库与安全归档”窗口，不改变 USB → WiFi → 远程的通道职责；当前远程仍未正式部署或接入三端。
- `%LOCALAPPDATA%\ZwmDeviceShareHub\content-history.db` 成为成功传送、设置与归档事件的本地真源；首次启动从 `transfer-history.tsv` 幂等迁移，旧文件保留，数据库失败时旧 TSV 仍可降级使用。
- 素材库读取用户设置目录的一级文件夹；从库内传送仍调用同一 `UploadToDevice`，因此保留内容指纹、按设备重复提醒、接收端 commit/USB 校验成功后才登记的边界。
- 安全归档顺序固定为：内容指纹 → 临时 ZIP → ZIP 结构/文件数校验 → 复制后 SHA-256 校验 → 原子命名归档包 → 写入 `archive_ready` → 原目录移入 Windows 回收站 → 写入 `archived`。不得改为先删源目录或静默覆盖同名 ZIP。
- 第二备用构建 run `30269352290` 已全部成功；最终 V4.0.0 命名与文档提交 `6950320` 的 run `30270126979` 也全部成功：Windows 编译与 `content_store_tests`、远程 Worker/DO/R2 HTTP 闭环、Android 正式构建/Lint 和 iPhone 测试/IPA 打包均通过。
- 最终 Windows EXE 已放桌面：`C:\Users\z\Desktop\素材投送中控-Windows-V4.0.0.exe`，SHA-256 为 `E8307BE321E90D5D14B2AC3873CB17B96E10E04EF1F126A36DE5E6CC7F7AEA5F`；旧版本未删除，可随时回退。
- 同一候选 EXE 已在本机启动并创建 49,152 字节 SQLite 数据库，诊断记录 `content_database_ready`；原 156 字节 TSV 保留未删除。自动化能识别主窗口“素材库”按钮，但 Windows 安全检查/单实例状态阻止了第二次自动打开归档子窗口，因此真实目录选择、临时样本归档和回收站恢复仍是发布前实体复核项。

## 2026-07-27 远程传送服务基础

- 新增 `remote-relay/`，使用 Cloudflare Worker + SQLite Durable Object + 私有 R2；当前候选包版本 `0.1.1`。
- 服务端已实现管理公钥绑定的工作区、电脑签发成员凭证、设备签名挑战、24 小时会话、WebSocket 在线状态、远程开关、设备撤销、密文任务和最长 24 小时清理。
- 0.1.1 增加 `POST /v1/presence`、`GET /v1/inbox`、`GET /v1/outbox` 和结构化请求日志，供 Android/iPhone 后台受限时以 HTTPS 心跳和轮询维持真实状态；对应协议测试从 8 项增至 10 项。
- 第二备用构建 run `30266289195` 已验证 0.1.1：Linux 实际启动 Worker、Durable Object 与本地 R2，并通过完整 HTTP 上传、下载和 ACK 删除；Windows、Android、iPhone 三项构建同 run 全部成功。功能提交为 `cec4889`。
- 文件名、用户路径、明文和明文密钥不进入云端清单；接收端 ACK、取消或过期后删除全部密文。
- 8 项 Node 协议测试、JS 语法检查、Cloudflare API 类型检查、npm 高危依赖审计和 Wrangler 部署预检通过；CI 已增加独立 `remote-relay-check`。
- 第二备用构建 run `30252544159` 的远程任务实际启动 Linux Worker，并完成管理员/成员身份、Durable Object 会话、R2 密文上传、接收端下载、ACK 和删除后不可再次读取的 HTTP 闭环；同一 run 的 Windows、Android、iPhone 三项也全部成功。
- Windows 本机 `workerd` 启动发生访问冲突；因此本机 DO/R2 仍未运行，当前集成证据来自 Linux CI。主账号与第一备用账号本轮都在任何步骤开始前因运行资源失败，不是代码或测试失败。
- 三端客户端尚未生成系统密钥、交换成员凭证或调用服务；Cloudflare 正式服务未部署，手机流量/异地 WiFi 未实测，Windows 继续保持 `remoteConnected=false`。
- 2026-07-27 再次恢复 Cloudflare 授权时，账号信息可读，但 Workers/R2 资源接口返回认证错误；Wrangler 登录页默认申请 28 项权限，超出当前部署所需范围，未替用户静默授权。此项是正式部署阻塞，不影响本地代码与 dry-run 证据。
- 权威协议：`docs/REMOTE_PROTOCOL_V1.md`；服务入口：`remote-relay/README.md`。
- 仓库边界再次确认：`zwmopen/team-video-workflow` 是唯一源码真源。两个备用私有仓库只在主账号额度不可用、准备实际执行构建时同步当次提交；不得把日常开发、文档收口或“多一份备份”作为同步理由。本轮 `d205630` 同步备用仓库是为了实际运行 CI，其中第二备用 run `30252544159` 提供了有效证据；后续 `df54b80/07d3054` 的纯同步不应作为惯例重复。

### 当前“能否使用”的准确口径

- Windows V3.9.1、Android 0.5.8、iPhone 0.5.2 的既有 USB/局域网 WiFi 能力仍是当前可用正式版；
- `remote-relay/` 是已通过 CI 集成验证的服务端基础，不是已经交付给用户的远程传送功能；正式服务未部署、三端客户端未接入、异网真机未验收，因此远程目前不能使用；
- 远程交互尚未完成：还缺首次无感登记通知、设备在线状态、自动选路、远程进度、断点恢复、可行动错误、撤销同步和三端设置入口；
- 第一性原理方向已经确定为“选设备和文件即可，USB → WiFi → 远程自动选择，用户不接触 IP/配对码/密钥”，但必须等三端真实闭环后才能称为完成了第一性原理升级。

## 2026-07-26 当前增量

- Windows V3.8：`windows-native/src/usb_transport.*` 实现 WPD/MTP 与 iPhone House Arrest 文件共享；`usb_bridge.py` 作为 EXE 资源内置。通道优先级 USB → Wi‑Fi。
- USB 只检测到充电/调试接口时，界面隐藏系统驱动名并提示把手机 USB 用途切换为“文件传输”；不把 ADB 作为正式传送依赖。
- 多手机同时在线时，未开放 MTP 的匿名 Android USB 接口保持独立卡片，不再靠“唯一在线安卓”猜测身份，防止关联到错误设备。
- Android 开放 MTP 后，以 USB 厂商和设备硬件标识把 WPD 与底层接口合并为同一张真实设备卡；不得退回按在线数量猜测。
- Windows 还会读取 `DEVPKEY_Device_BusReportedDeviceDesc`（例如 `Redmi K60`）作为同实体匹配与友好名称的可靠补充，不显示 ADB/Composite 驱动描述。
- WPD 枚举会丢弃无名称、无型号且不可打开的系统残留条目；`usb_wpd_open_failed` 只记录友好名称和错误，不记录设备路径、序列号或配对信息。
- MIUI 若把同一实体拆成“唯一匿名可写 WPD + 唯一有名底层 USB”，可安全配对并继承真实名称；任何一侧不唯一时保持分开，避免跨手机误关联。
- 传送历史：`%LOCALAPPDATA%\ZwmDeviceShareHub\transfer-history.tsv`，只在接收端提交/校验成功后写入；文件夹指纹包含相对路径、大小和逐文件 SHA-256。
- Android 0.5.7/code34：外部来源消失后二次确认并清除私有副本。K60 真机测试前备份位于 `D:\AICode\运行数据\device-share-hub\backups\k60-before-source-reconcile-20260726.tgz`。
- 真机证据：K60 外部 `Download/Lark` 为 24 个作品；正式 0.5.7 覆盖后首页 24，两个幽灵活动项和两个幽灵回收项已清除，目录授权保留。
- 互联网中继尚未部署；不得把同包名当成身份，也不得提前开放无后端的假开关。详细约束见 `docs/REMOTE_CHANNEL.md`。
- 最终三端构建 run `30189534099` 对提交 `a27b62c` 全部成功；公开发布为 `gallery-updates v0.5.7`。APK/IPA/EXE SHA-256 分别为 `A8571C2D7D997D4397653425B0B9F1ED1C04E05A1D196638A950F6FC6D4AC027`、`C22E9C32569FDDFFBB6C342C959DC328BB5AC8D8C85418BC1700601980215761`、`DC9CC5E53C7DCCE98FF10CDEBF6A762F8800EFF6BF23E1176A1E22C297ED6552`。
- K60 已用公开同源 APK 覆盖并保持 24 个作品；Windows V3.8 已放桌面。K60 打开 MTP 后，界面从两条系统接口收敛为一张 `Redmi K60` 卡片并显示 USB 可用。当前自动文件选择受前台窗口焦点影响，未完成 App 内真实 USB 文件提交，不能用枚举证据替代落盘证据。
- Windows V3.9 增加设备级 USB/WiFi/远程传送权限持久化与右键菜单；设备首次登记默认允许，卡片只显示真实在线通道。Android 0.5.8 与 iPhone 0.5.2 会持久记录在局域网发现过的电脑身份。远程中继服务尚未部署，因此“远程”不会显示为已经连通。
- 最终构建 run `30191579830` 对功能提交 `74858f4` 的 Windows、Android、iPhone 全部成功。公开发布为 `gallery-updates v0.5.8`；APK/IPA/EXE SHA-256 分别为 `F3AD74B9F47FF98ECF28B5E145D641BB0227445A2FF5062300FB2D4A077B8B34`、`C1860FE11B9E766DA7729FBF859756E28D94BFEB992847BBEC9AA21531C6A7D9`、`9068E010BA94A87AFD2AA4C27BF12483951DB7275F8A2FA8AB4C5B1F1990EFE9`。
- Redmi K60 已覆盖安装最终 Android 0.5.8/code35，安装前后均为 22 个作品；传送页实体发现电脑与另一台手机并显示 WiFi。iPhone 本轮未连接，不能把云端构建和 IPA 结构校验写成实体安装结果。
- Windows V3.9.1 把同一台 K60 的 `Xiaomi K60`、`Redmi K60`、`Xiaomi 23013RK75C`、`mondrian` 归为同一硬件身份；设备卡只保留一行，右侧按 USB → WiFi → 远程显示真实可用标签。
- V3.9.1 在 Redmi K60 完成两次独立实机传送：Windows 原生 MTP/USB 写入 `Download/Lark` 成功，局域网 WiFi 任务提交成功；两次均从手机拉回核对 SHA-256 一致，测试文件随后从电脑和手机精确删除。
- 第三构建账号 `idmzwm-sys` 已通过 GitHub 官方设备授权接入，本机构建镜像为私有仓库 `idmzwm-sys/team-video-workflow-build`。run `30193335275` 对提交 `a825fb9` 的 Windows、Android、iPhone 三项全部成功；它只作为额度兜底，不是源码真源。
- Windows V3.9.1 当前桌面 EXE SHA-256 为 `7F7A1925462E63BA69B84ACEC9F436446142F8E1B9D3633D8205DD91AC2097B5`；最大化 2560×1600 与默认窗口均实测显示右侧通道标签。远程后端尚未部署，因此实体测试只显示 `USB · WiFi`，不显示“远程”。

更新时间：2026-07-26

## 当前可交付基线

| 项目 | 当前值 |
|---|---|
| Windows | 素材投送中控 V3.9.1，原生 Win32 x64 |
| Android | 相册 0.6.0，versionCode 38，`com.zwm.gallery` |
| iPhone | 相册 0.5.2，build 21，源码 bundle id `com.zwm.album` |
| 当前主要变化 | 设备通道权限、平台图标、备注层级、来源一致性修复、内容指纹去重与 USB → Wi‑Fi 自动回退 |
| 当前功能提交 | 发布后以本文件所在提交为准 |
| 主源码 | <https://github.com/zwmopen/team-video-workflow> |
| 构建备用仓库 | `rpgzwm/team-video-workflow-build`、`idmzwm-sys/team-video-workflow-build`，只用于主账号额度不足时运行三端构建 |
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
| `remote-relay/` | 远程设备身份、在线会话、短期密文中继与自动清理 |
| `docs/PROTOCOL.md` | 局域网发现与 HTTP 传输协议 |
| `docs/REMOTE_PROTOCOL_V1.md` | 远程身份、签名、密钥封装和中继协议 |
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
