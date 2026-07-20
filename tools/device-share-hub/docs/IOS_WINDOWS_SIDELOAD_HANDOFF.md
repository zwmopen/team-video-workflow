# iOS Windows 侧载交付与排障手册

## 交付结论

当前源码版本：`0.4.7` build 18，同一 IPA 兼容 iOS 12+；互传列表保留原设备名并追加“（作品数 N）”。当前实体安装证据沿用已实际覆盖的版本；0.4.7 构建与安装结果以 `MAINTAINER_HANDOFF.md` 的后续记录为准。

0.4.6 构建来源：备用构建仓库 GitHub Actions `29727724327`，模拟器测试和真机 SDK 编译成功；IPA 已核对版本 0.4.6、build 17、最低 iOS 12.0，SHA-256 为 `23A765CFB15B03B7E712202B8D2926419A885EF1456AA5189BDE5648400C49F6`。安装包已发布并放到桌面，实体覆盖安装后再记录设备显示的作品数。

苹果官方“文件”App 不属于本项目安装包。设备删除该系统 App 后只能通过 App Store 恢复；Sideloadly、AltStore 和未预签名 IPA 不能替 App Store 静默安装。相册的局域网接收与 iOS 12 App 内文件夹选择不依赖“文件”App。

当前实体安装版本：`0.4.5` build 16（iOS 12+ 同包兼容、递归扫描、数量徽标、真实“相册回收站”、App 内目录选择和局域网接收）

构建来源：备用构建仓库 GitHub Actions `29723234708`，Windows、Android、iOS 三个任务均成功。

交付文件：

- `album-iOS-v0.4.5-altstore.ipa`
- `album-iOS-v0.4.5-altstore.ipa.sha256`
- SHA-256：`C6D47ADE47685F6D13F1A8E59E25BDA8F002D21CBA14C9BFB37BA6E7ED80ACBD`

实体 iPhone 安装结果：Sideloadly 0.60.0 完成覆盖安装；设备应用清单确认版本 `0.4.5`、build 16、最低系统 iOS 12.0。免费签名仍为 7 天，后台续签会提前刷新。

测试边界：以上证明构建产物可在实体 iPhone 上签名并安装；文件夹授权、作品扫描、多图分享和跨日清理按实际操作结果分别记录。

## 推荐安装路径（Windows）

1. 安装 Apple 官网版本的 iTunes 和 iCloud，不使用 Microsoft Store 版本。
2. 数据线连接 iPhone，解锁并选择“信任此电脑”；先确认 iTunes 能显示 iPhone。
3. 安装 Sideloadly 0.60.0 或更高兼容版本，选择 iPhone 并加载 IPA。
4. 用户本人输入 Apple ID 和密码。密码不得发到聊天、写入脚本、日志、仓库或发布资产。
5. 等待界面显示 `Done. 100%`，不要只依据桌面图标或无报错推断成功。
6. iPhone 如阻止启动，在“设置 → 通用 → VPN 与设备管理”信任开发者；iOS 16 及以上按系统提示开启“开发者模式”。
7. 保持电脑和手机处于同一 Wi-Fi，并让 Sideloadly 后台续签服务运行。免费 Apple ID 的签名有效期为 7 天。

推荐只让 `sideloadlydaemon` 随 Windows 登录启动。它空闲时 CPU 基本为 0，本次实测约占 32 MB 内存，并按约 96 小时刷新；Sideloadly 主界面平时可关闭。电脑无需一直开机，但应在 7 天内至少有一次“电脑开机 + 手机同一 Wi-Fi”的续签机会。

AltStore 仍可作为备选，但本次 Windows 实机交付最终使用 Sideloadly 完成。应用本身不需要管理员、越狱、Root、ADB、无障碍、设备管理、手动 IP 或配对码。

## 第一性原理诊断顺序

侧载不是一个步骤，而是六层链路。必须从下到上定位，避免反复重装：

1. **USB 层**：Windows 设备管理器能看到 Apple Mobile Device 与 iPhone。
2. **Apple 通道层**：iTunes 能显示 iPhone；仅有 USB 设备并不代表 Apple 协议可用。
3. **侧载工具层**：Sideloadly 的设备列表出现 iPhone。
4. **账号层**：Apple ID 验证和双重认证通过。
5. **签名与安装层**：设备注册、描述文件、应用校验和写入完成。
6. **应用层**：开发者信任、开发者模式、启动和业务功能逐项验收。

每一层只证明自己，不得把“CI 编译成功”写成“实体机测试通过”，也不得把“安装成功”写成“业务功能通过”。

## 本次踩坑与固定解法

### IPA 内部名称必须使用 ASCII

失败包把内部目录和可执行文件命名为 `相册.app/相册`。macOS `zip` 生成的条目没有 UTF-8 标记，Windows 侧载工具按 CP437 解码后文件名乱码，而 `Info.plist` 仍寻找“相册”，最终提示 `Invalid file`。

固定规则：

- 内部产物始终使用 `Payload/Album.app/Album`；
- `PRODUCT_NAME`、`CFBundleName` 使用 `Album`；
- 仅 `CFBundleDisplayName` 使用“相册”；
- CI 打包后必须自动断言存在 `Payload/Album.app/Album`，并拒绝非 ASCII ZIP 条目。

### 代理要检查进程实际优先级

本机同时存在可用的 HTTP 代理端口和失效的 SOCKS 端口。Python 网络库优先读取 `ALL_PROXY`，导致连接 `gsa.apple.com` 时出现 `ProxyError`，AltServer 则只显示笼统的 `Server returned invalid response`。

固定规则：

- 同时检查 `ALL_PROXY`、`HTTP_PROXY`、`HTTPS_PROXY`、Windows 系统代理和实际监听端口；
- 只修正当前侧载进程的代理环境，不擅自覆盖用户全局网络设置；
- 先验证苹果认证域名可连通，再让用户重复输入密码。

### “USB 可见”不等于“iTunes 可见”

iPhone 锁屏、重新插线或 Apple Mobile Device Service 卡住时，Windows 仍可能显示 USB 设备，但 iTunes/Sideloadly 显示无设备。

固定顺序：解锁并保持屏幕亮起 → 重新插线 → 必要时重新信任 → iTunes 验证 → 关闭可能争用设备的 iTunes/AltServer → 刷新 Sideloadly。只有服务确实卡住时才以管理员权限重启 Apple Mobile Device Service；这项权限只属于电脑维护，不属于手机 App。

### 错误含义要按阶段判断

- `Login failed (-22406)`：苹果服务器判定 Apple 账号密码错误；不能用 QQ 邮箱密码或应用专用密码替代。
- `Invalid file`：账号已通过，问题在 IPA 结构或内容，不要继续折腾密码和手机信任。
- `Prefetching Anisette 0%` 长时间不动：优先检查代理和苹果认证网络。
- `Making sure device ID ... is registered`：正在注册设备，可能需要等待苹果服务器。
- `Installing ... VerifyingApplication`：已进入实体机安装阶段。
- `Done. 100%`：侧载工具确认安装完成；随后仍需验证手机启动和业务功能。

### 权限与凭据边界

- Apple ID 密码和验证码只能由用户本人输入。
- 仓库、诊断文档、发布说明不得保存 Apple ID、密码、验证码、设备 UDID、配对记录或完整个人路径。
- IPA 是未预签名构建产物，不包含用户证书和账号信息。
- 不通过伪装系统相册、自动点击、越狱、Root、ADB 或设备管理权限规避平台检测。

## 后续版本发布清单

1. 同步更新 `MARKETING_VERSION`、`CURRENT_PROJECT_VERSION`、`Info.plist`、README、CHANGELOG 和 artifact 名称。
2. iOS 内部 bundle/可执行文件名保持 ASCII；显示名称单独配置。
3. CI 编译后检查 IPA ZIP 条目、`Info.plist`、可执行文件存在性和 SHA-256。
4. 至少完成一次实体机签名安装，并记录工具版本和最终状态，不记录账号和 UDID。
5. 业务功能逐项操作并记录结果；未操作的项目不宣称测试通过。
6. GitHub Release 保存 IPA、校验文件和脱敏发布说明，便于后续复用和回滚。
