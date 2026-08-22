# 2026-08-22 Cloudflare 中继与混合传输当前真相

## 2026-08-23 混合通道状态合并修复（Beta3 已发布）

- Windows 源码目标版本提升为 V4.3.23；Android 0.6.46/versionCode 84、iPhone 0.6.33/build 52 保持不变。
- 新增独立 Wi‑Fi 最近观察时间，并合并局域网探测与远程中继设备记录，避免旧 IP 遮蔽远程 P2P/HTTPS 回退，也避免远程凭证和库存被 LAN 探测清掉。
- run `32589239907` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta `v0.6.46-beta.3` 已发布，桌面包已同步到 `C:\Users\z\Desktop`，稳定更新索引不改。
- 待完成：真机 USB/Wi‑Fi/P2P/HTTPS/ACK/自动补货验收；当前桌面端已重启到 V4.3.23，但日志仍需手机在线后取得设备证据。

## 2026-08-23 远程库存合并边界修复（Beta 0.6.46-beta.2 已发布）

- Windows 已提升到 V4.3.22；远程心跳缺字段不再覆盖局域网发现的完整库存、版本和更新能力。Android 0.6.46/versionCode 84、iPhone 0.6.33/build 52 保持不变。
- Device Share Hub run `32587368303` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.46-beta.2>。
- 新桌面包已同步到 `C:\Users\z\Desktop\device-share-hub-Windows-V4.3.22-relay-beta.exe`；稳定索引和 AltStore Beta 源不变。真机仍需验证远程 P2P/HTTPS、落库、ACK 和精准阈值补货。

## 2026-08-23 远程库存心跳与自动补货闭环（Beta 已发布）

- 当前源码目标版本为 Windows V4.3.21、Android 0.6.46/versionCode 84、iPhone 0.6.33/build 52。
- Android/iPhone 远程心跳现在携带分类库存；Cloudflare Durable Object 做计数校验并在 `/v1/devices` 返回库存与版本能力；Windows 合并后统一使用 USB/Wi-Fi/远程可用判断。
- 本轮协议测试、自动更新/库存静态门禁已通过；Device Share Hub run `32586026767` 的三端云构建和线上 Worker E2E 全部通过，Beta `v0.6.46-beta.1` 已发布，三端包已同步到 `C:\Users\z\Desktop`。稳定索引不改；真机仍需验证远程 P2P/HTTPS、作品落库、ACK 和精准阈值补货。
- Cloudflare Worker 最新部署版本为 `74aa46b2-8122-437c-93be-844262af7004`；AltStore Beta 源提交为 `4578bce603ea5476252f25bc8a74c2ef719e30b5`。

## 2026-08-23 自动补货递归发现修复（Beta Windows V4.3.20，已发布）

- 现场复核发现精准作品存放在 `成品库\\微信公众号\\作品集_xxx[转]` 子目录，而旧实现只扫描 `library_path` 第一层，导致自动补货找不到精准源。
- `PickAutoRestockSource` 已改为递归扫描并继续严格匹配 `[转]` / `【转】` 与图文作品结构；run `32583765953` 全部通过，Beta patch 为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.3>。实体手机验收仍待连接，稳定索引不改。

## 2026-08-22 自动分发默认配置修复（Beta Windows V4.3.19，已发布）

- 复核当前电脑数据库发现自动补货开关没有记录，导致自动分发逻辑实际被关闭；已将当前数据库设置为自动手机更新开启、自动补货开启、精准阈值 5，并把原库备份到临时目录。
- 源码新增 ContentStore 默认迁移：只对缺失设置写默认值，不覆盖用户明确关闭或自定义阈值；Windows 版本提升到 V4.3.19，手机版本保持已发布的 Android 0.6.45/iOS 0.6.32。
- Device Share Hub run `32581609531` 的 Windows、Android、iOS、remote-relay check 和 live E2E 全部通过；Beta patch 为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.2>，AltStore Beta 源提交为 `83de151d181976cb72e7389790acfaa12cb2eec5`。
- 当前待手机上线后的真实自动补货/USB/Wi-Fi/P2P/HTTPS 中继回归；稳定索引不改。

## 2026-08-22 自动更新重试与分类库存信标修复（Beta 0.6.45/0.6.32/V4.3.18，已发布）

- 现场发现旧 Android 0.6.29/versionCode 67 曾广播 receiving，但 /v2/info 接收端口无响应；这证明“设备出现在列表”不能等同于 HTTP 接收器可用，也暴露了自动更新失败后的持久化抑制风险。
- Windows 自动手机更新现在把“发送中”与“已送达”分开：只有 UploadToDevice 成功返回后才写 auto_mobile_update_delivered_<deviceId>；失败路径清除内存占位并保留下一轮推送资格。
- Android/iPhone 在线信标追加可选 conversion|traffic|uncategorized 尾字段；Windows 解析后优先使用分类字段，旧客户端没有尾字段时继续保持未知，不使用总数替代精准流量。
- 新增 scripts/verify-auto-mobile-update.mjs，Windows 云构建前检查返回值、成功后持久化和失败重试分支；本轮版本号已提升为 Android 0.6.45/versionCode 83、iPhone 0.6.32/build 51、Windows 4.3.18。
- Device Share Hub run `32579462284` 的 Android、iOS、Windows、remote-relay check 和已部署 Worker live E2E 均通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.1>。三端包已同步到 `C:\Users\z\Desktop`，哈希已写入 CHANGELOG；AltStore Beta 源提交为 `a0591f1401365e4ae252882083ea9eda51082f8e`。
- 稳定 `latest.json` 保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。Beta 发布不等于实体设备验收：当前仍需 Android、iPhone、Windows 实机验证安装、在线心跳、USB/Wi-Fi/P2P 成功、HTTPS 中继回退、作品落库、ACK 和精准低于 5 的自动补货。

## 2026-08-22 P2P ACK 丢失后的重复入库修复（Beta 0.6.44/0.6.31/V4.3.17 已发布）

- 已修复 Android 的真实幂等性缺口：P2P 已写库但 ACK 丢失、Windows 转 HTTPS 中继重试时，Android 现在只补发 ACK，不会再次下载或写入作品库。
- 成功 ACK 后 Android 会移除远程收件内存占位；重复 P2P 完成路径会释放活动引擎并清理缓存。iOS 原有的中继去重逻辑保持，并补齐重复 P2P 路径的活动引擎释放。
- 本地已通过隐私检查、remote-relay check/typecheck、13/13 协议测试和源码不变量检查；Device Share Hub run `32575362864`、Repository quality `32575362946`、Secret scan `32575362919` 和线上 Worker E2E job `97036844830` 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.44-beta.1>；Android SHA-256 `0fe499cf1040d12c28ba9ef8c5aa8e7f2d214475d6de7c88e985f27d4430381d`，iPhone IPA SHA-256 `1db6768df098beb2c8924d5b7fb0b923d81ff53678b840bc297787fe1cd25c92`，Windows SHA-256 `f89106a50bd4d73295a098e803382648680c3fbd90d642d70499a0c736a4af52`。
- 三端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源内容提交为 `f46cca7fd98504ea4c195e89a75a6697a80d197a`，稳定 `latest.json` 未改动。

## 2026-08-22 Windows 中继失败清理与 Beta 0.6.43（Beta 已发布，真机验收待完成）

- 当前源码版本已同步为 Windows 4.3.16、Android 0.6.43 / versionCode 81、iPhone 0.6.30 / build 49。
- Windows 远程中继在上传、提交、进度或哈希失败后，现在会立即取消本次 transfer，清理 R2 临时对象和收件箱任务，并重置失败的 transferId，避免孤立任务等待 TTL 或下一轮误重试。
- Device Share Hub run `32571763937`、Repository quality run `32571763918`、Secret scan run `32571763916` 均通过；其后的文档收口 run `32572517530` 的 Android、iOS、Windows、remote-relay 和正式 Worker E2E job 也全部通过。
- Beta 发布页为 https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.43-beta.1；AltStore Beta 源已指向 iPhone 0.6.30/build 49。三端新包已同步到 `C:\Users\z\Desktop`；稳定 `latest.json` 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。
- 当前没有连接实体 Android/iPhone/Windows；真机权限、安全扫描、P2P 成功、HTTPS 自动回退和文件实际落库验收仍未完成，不能用云端构建替代这些证据。

## 2026-08-22 Android P2P ICE 候选竞态修复与 Beta 0.6.42（Beta 已发布，真机验收待完成）

- 当前源码版本已同步为 Windows 4.3.15、Android 0.6.42 / versionCode 80、iPhone 0.6.29 / build 48。
- Android P2P 的 ICE 候选入队和远端 Description 回调现在通过 iceLock 原子排空，修复候选在并发窗口中丢失导致直连失败的风险；此前的跨线程状态可见性修复保持不变。
- GitHub Actions Device Share Hub run 32565416952 的 Android、iOS、Windows、remote-relay 全部通过；Repository quality run 32565417059、Secret scan run 32565416950 也已通过。Beta 发布页为 https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.42-beta.1，AltStore Beta 源已同步；没有本地 Android Gradle/Xcode/Windows 构建替代证据。
- 当前没有连接实体 Android/iPhone/Windows，真机权限、安全扫描、P2P 成功和 HTTPS 中继回退验收仍未完成。

## 2026-08-22 线上 Cloudflare Worker E2E 门禁

- 代码头 `8846dc23` 新增 `remote-relay-live-e2e` 云端 job；它不使用本机网络或缓存，直接访问 `https://zwm-device-share-relay.zwmrpg.workers.dev`。
- Device Share Hub run `32568228480` 中，`remote-relay-live-e2e` job `97019921822` 已通过；真实跑通 health、工作区登记、管理员/成员会话、presence、P2P offer/answer/close、R2 上传、commit、收件箱、下载 SHA-256、ACK 和 R2 删除共 14 个阶段。
- 这项证据证明正式 Worker、Durable Object、R2 和 P2P 信令控制面在线可用；不证明实体 Android/iPhone 的 DataChannel 建连、文件落盘、HTTPS 自动回退或系统权限安全扫描，后四项仍需真实设备。
- 本机 Windows 直连 `workers.dev` 受系统网络代理影响，Node E2E 可能出现连接超时；正式 E2E 以 GitHub Ubuntu runner 结果为准，避免把本机代理差异误判为服务故障。

## 2026-08-22 Android P2P 共享状态可见性修复与 Beta 0.6.41

- 当前源码版本已同步为 Windows 4.3.14、Android 0.6.41 / versionCode 79、iPhone 0.6.28 / build 47。
- Android P2P 的 WebRTC 回调、信令轮询、文件处理队列和超时任务共享状态现在使用 volatile，修复跨线程读到旧状态导致误超时或重复清理的风险。
- GitHub Actions Device Share Hub run 32562005856 已通过，Repository quality run 32562005857、Secret scan run 32562005864 已通过；remote-relay 13/13 通过。没有使用本地 Android Gradle/Xcode/Windows 构建替代证据。
- Beta 发布页：<https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.41-beta.1>；Android SHA-256 e9bd39eb83b1bdc516c4824fdb4a451cdad97dc31d741d16fc0a9f8de9989cb5，iPhone IPA SHA-256 b03f6fa4ba9830b88f059fc1a5fe41f07d4b24d8e852ec6049c7e77e4a28deed，Windows SHA-256 a65a325f248e397cba5eacc8057ebffe03227c8cdb3296720353d9d9c11b5bd0。
- 桌面包路径：C:\Users\z\Desktop\album-Android-v0.6.41.apk、C:\Users\z\Desktop\album-iOS-v0.6.28-altstore.ipa、C:\Users\z\Desktop\device-share-hub-Windows-V4.3.14-relay-beta.exe；AltStore Beta 源内容提交 e58a7b76cb0ecb96b7de05e519f89acf6fc27b41。稳定 latest.json 未改动。
- 当前没有连接实体 Android/iPhone/Windows，真机权限、安全扫描、P2P 成功和 HTTPS 中继回退验收仍未完成。

## 2026-08-22 Android 媒体权限残留修复与 Beta 0.6.40

- 当前源码版本已同步为 Windows `4.3.13`、Android `0.6.40` / versionCode `78`、iPhone `0.6.27` / build `46`。
- 删除 Android Manifest 中遗留的 `READ_MEDIA_IMAGES` 与 `READ_MEDIA_VISUAL_USER_SELECTED`；自动截图、悬浮窗、截图观察器和自动剪切板链路此前已删除，本轮把权限声明也收干净。
- Android 10 隐藏作品兼容导入仍保留旧存储兼容通道；它只在用户主动选择作品文件夹后工作，不等同于系统相册自动扫描。若后续决定彻底放弃 Android 10 隐藏目录兼容，再单独评估删除旧存储权限和导入代码。
- GitHub Actions Device Share Hub run `32559885341` 已通过：Android 2m31s、iOS 4m21s、Windows 9m05s、remote-relay 22s；Secret scan `32559885315`、Repository quality `32559885563` 也已通过。没有用本地 Android Gradle/Xcode/Windows 构建替代证据。
- Beta 发布页：<https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.40-beta.1>；Android SHA-256 `f14d708707c8d1ed5be3ae81e0873f644ec5d30d2496592439a6898cba2d6faa`，iPhone IPA SHA-256 `98d671107d6ad686870fcebdd1f64a0198960e14276cd532ac2b6baeabbbd61f`，Windows SHA-256 `994a20ecb0ef01f533bb99e71dfacf1d73886c019127e986ef5a54d48cbcff23`。
- 桌面包路径：`C:\Users\z\Desktop\album-Android-v0.6.40.apk`、`C:\Users\z\Desktop\album-iOS-v0.6.27-altstore.ipa`、`C:\Users\z\Desktop\device-share-hub-Windows-V4.3.13-relay-beta.exe`。AltStore Beta 源内容提交 `d64b3df036109a52fa62b1fc997cb938419d9b6e`，稳定 `latest.json` 未改动。
- 真实 Android/iPhone/Windows 设备仍需安装后检查系统权限列表、安全扫描、作品收发、P2P 成功和失败回退；在设备未连接前不宣称真机完成。

## 2026-08-22 Android P2P 会话回收修复与 Beta 0.6.39

- 当前已发布 Beta：Windows `4.3.12`、Android `0.6.39` / versionCode `77`、iPhone `0.6.26` / build `45`。
- 本轮修复 Android P2P 启动瞬间失败后的残留活动引擎：失败引擎不再进入 map，下一轮轮询可以重试；取消已结束引擎也不会提交到已关闭队列。上一轮 20 秒建连超时、回调线程隔离和 HTTPS 中继回退保持不变。
- 修复版 Beta：<https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.39-beta.1>；三端代码构建 run `32558264352`（PR #24 代码头 `f69775559f643e15a21095da5b86b61a270993da`），安全扫描 run `32558264343`、质量检查 run `32558264394` 已通过。
- Android SHA-256：`ff6153918f9c8ac7793e9ce7a0c1284ed20e63243a94c8780d106a41e0bd3f23`；iPhone IPA SHA-256：`189cd17b4904dd97b8fa750559fcb6eeabb018a3274c1367967c2534339b8506`；Windows SHA-256：`ea87a27b79198e091a09e927c0a3369bc919bfd3846ac38210bd284a4d28bb13`。
- 桌面同步包：`C:\Users\z\Desktop\device-share-hub-Windows-V4.3.12-relay-beta.exe`；Android：`C:\Users\z\Desktop\album-Android-v0.6.39.apk`；iOS：`C:\Users\z\Desktop\album-iOS-v0.6.26-altstore.ipa`。
- iPhone Beta 更新源：<https://raw.githubusercontent.com/zwmopen/gallery-updates/refs/heads/main/altstore-beta.json>，当前指向 0.6.26/build 45；稳定 `latest.json` 仍保持 Android `0.6.29` / versionCode `67`、iPhone `0.6.16` / build `35`，未把 Beta 推入稳定自动更新。
- 已接入真实数据面：Windows 使用 libdatachannel 发起 `album-transfer-v1`，Android/iOS 使用 WebRTC 接收；Cloudflare 只承担短时 SDP/ICE 信令，文件字节不上传 R2。
- P2P 失败条件（20 秒建连超时、ICE/DataChannel 失败、manifest/大小/SHA-256/作品库写入失败）统一回退现有 HTTPS 中继；中继仍是跨网络必达兜底。
- P2P 收件端只有在文件完整校验并写入现有作品库后才 ACK，并用 `transferId` 去重，避免 ACK 丢失后的中继回退造成重复作品。
- 本阶段已通过本地协议检查、远程 Worker 13 项测试和 run `32558264352` 三端云构建；Beta 已发布。没有实体 Android/iPhone/Windows 跨网络设备，暂不宣称真实 P2P 业务已验收。

### 上一版普通 HTTPS 中继基线（历史证据）

- 当前开发交付版本：Windows `4.3.7`、Android `0.6.34` / versionCode `72`、iPhone `0.6.21` / build `40`。
- 已补齐：Windows 发现手机后自动下发远程成员凭证；Android/iOS 保存凭证、带工作区头登录中继、心跳、下载、写库和 ACK；Windows 无 USB/Wi-Fi 时自动走 Cloudflare 中继。
- 已用实际 Worker 烟测跑通：健康检查 → 注册工作区 → 管理端/接收端会话 → 在线心跳 → 创建任务 → R2 上传 → 提交 → 收件箱 → 下载 SHA-256 → ACK → R2 删除。
- Beta 安装包：<https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.34-beta.1>；对应源码提交 `7c72f43`，GitHub Actions `32469716023`。

- Worker 已部署：`https://zwm-device-share-relay.zwmrpg.workers.dev`。
- 资源已实读：Worker `zwm-device-share-relay`、Durable Object `WorkspaceRelay`、R2 `zwm-device-share-relay`；当前部署版本 `b4fc48b0-9b3b-4e73-b828-d51abe59ba4c`，100% 生效。该版本由当前工作树部署，包含最新 P2P 会话授权与异常清理修复。
- 当前 Cloudflare 账号没有活动 Zone，所以暂时不能绑定自定义域名；本机网络对 `workers.dev` 域名存在 DNS/连接异常，不能用本机失败的健康请求否定部署证据。
- 传输策略：同 Wi-Fi/USB 仍走现有直传；远程中继作为不同网络的 HTTPS 兜底。Cloudflare 已加入短时 WebRTC/ICE 信令协商接口，三端原生 DataChannel 数据面仍未完成，暂时不宣称跨网 P2P。
- 移动端的 `RemoteRelayProfile` 由 Windows 在可信局域网首次发现时自动下发，不要求用户填写地址、令牌或配对码。

## 2026-08-20 Android 0.6.29 / iPhone 0.6.16 更新闭环优化

- Android 从后台回到前台且超过 6 小时未检查时，会静默检查并准备更新；启动检查、下载断点、SHA-256 校验和系统安装确认保持不变。
- iPhone 检查到新版本时，提示直接打开 AltStore 的 My Apps 更新，或复制 AltStore 更新源；不再只提示连接电脑。
- Android `versionCode=67` / `versionName=0.6.29`；iPhone `0.6.16/build 35`。

## 2026-08-21 高级远程传送：普通公开作品中继链路（历史开发记录）

- Android `0.6.33` / versionCode `71` 与 iPhone `0.6.20` / build `39` 已把中继收件箱接到真实文件处理：下载对象 → SHA-256/大小校验 → 写入现有作品库 → 最后 ACK。
- 本阶段按用户要求不做应用层端到端加密；文件是公开作品，远程服务只保存 HTTPS 传来的普通 ZIP 临时对象，ACK、取消或过期后删除。旧加密对象字段仍保留兼容读取。
- 桌面端新增 `remote-relay/scripts/send-public-work.mjs`：输入作品文件夹或 ZIP、接收手机设备 ID 和已建立的 Bearer 会话，即可创建任务、上传、提交。目录使用普通 ZIP，作品名里的 `[转]`/`[泛]` 会保留给手机端分类识别。
- Android `OnlineService` 与 iOS `IncomingTransferService` 都在写库成功后才 ACK；失败时移除本地“已处理”占位，保留中继任务等待下一轮重试。已确认导入的任务会持久化去重，避免 ACK 失败导致重复作品。
- `remote-relay/test/protocol.test.js` 新增普通公开作品完整协议测试：创建、上传、提交、收件箱、对象下载标记、ACK 删除；旧加密兼容测试继续保留。
- 当时尚未完成 Cloudflare 正式部署、Windows 原生面板接入和真实 Android/iPhone 安装；现已由顶部当前真相和 Beta 版本接续。实体手机安装与异地网络业务实传仍需单独验收，不能把协议测试当作真机跨网证据。

## 2026-08-21 高级远程传送：移动端中继会话客户端（历史开发记录）

- 当前开发候选版本：Android `0.6.31` / versionCode `69`；iPhone `0.6.18` / build `37`。

- Android `RemoteRelayClient` 与 iOS `RemoteRelayClient` 已镜像接入中继控制面：`POST /v1/challenges`、`POST /v1/sessions`、`POST /v1/presence`、`GET /v1/inbox`、`GET /v1/transfers/{id}`。
- 登录签名由本机系统安全存储中的 P-256 私钥完成；客户端只接受 HTTPS，不把 Bearer 会话令牌写日志。收件箱为空时返回空集合，任务 ID 做字符白名单校验，响应体限制为 2 MiB。
- 两端已增加登记资料存储层：保存中继地址、公开成员凭证和管理员签名，未保存私钥或 Bearer 令牌；未登记时不启动任何远程请求，旧局域网接收路径不变。
- Android 前台接收服务、iOS 局域网接收服务已挂接独立的 10 秒心跳调度器；它不占用局域网 V2 监听线程，远程登录失败只清除内存会话并等待下轮，不影响局域网收发。
- 两端当时只在心跳成功后读取 `/v1/inbox`，保留当前接收设备的 `ready` 任务并校验元数据；该限制已由上面的普通公开作品收件链路接续。
- 远程基地址只允许 HTTPS origin，不允许路径、查询、片段或用户信息；失败重试采用 10 秒起、最长 5 分钟退避，地址/凭证签名/设备 ID 变化会清空旧会话并立即重新认证。
- 该历史提交不代表 Windows 原生面板按钮化、已部署 Cloudflare 或已完成跨网络实传；当前以普通公开作品脚本发送和本地协议测试作为开发验收入口。

## 2026-08-20 Android 0.6.28 / iPhone 0.6.15 AltStore 自动更新源

- 云端发布流水线新增 `gallery-updates/altstore.json`，首个版本放在 `versions[0]`，后续每次发布 IPA 都会自动更新该源。
- 用户首次在 AltStore 添加 `https://raw.githubusercontent.com/zwmopen/gallery-updates/main/altstore.json`；之后 AltStore 自动发现新版本，AltServer 负责同一 Wi-Fi 下的侧载和免费证书刷新。
- 更新仍需 AltStore 的一次确认，iOS 不允许相册 App 静默替换自身；云端构建、公开源、Release 和真实手机安装仍分别核验。
- 版本：Android `versionCode=66` / `versionName=0.6.28`；iPhone `0.6.15/build 34`。

## 2026-08-20 Android 0.6.27 / iPhone 0.6.14 分类名称统一

- Android 顶部分类按钮和 iOS 顶部筛选统一使用“精准流量”和“泛流量”。电脑端自动补货的精准库存口径已经使用同名语义。
- 只改显示文案：内部 `conversion`/`traffic` 字段、`[转]`/`【转】`/`[泛]`/`【泛】` 识别、作品库存上报和自动补货逻辑不变。
- 版本：Android `versionCode=65` / `versionName=0.6.27`；iPhone `0.6.14/build 33`。云端构建、Release、`latest.json` 和真实手机安装仍分别核验。

## 2026-08-20 Android 0.6.26 / iPhone 0.6.13 平台按钮即时状态

- 作品卡片的抖音和小红书按钮采用“已点击即灰、仍可点击”的平台独立状态；预览按钮保持不变。Android 点击后先做本地即时反馈，回到主界面再从 `WorkLibrary` 持久化次数重绘；iOS 点击时即时反馈，`WorkLibrary.prepareShare` 失败时由页面重绘回滚。
- 版本：Android `versionCode=64` / `versionName=0.6.26`；iPhone `0.6.13/build 32`。Windows 本地不执行 Android Gradle/Xcode 构建，必须以 GitHub Actions 的 Android/iOS 产物、Release 和 `latest.json` 为云端交付证据。
- 发布工作流必须把 Android APK、iOS IPA 及各自 SHA-256 一起上传到同一版本 Release，并同时更新 `latest.json` 的顶层 Android 字段和 `.ios` 对象；不能只看到 Android 发布成功就结束。
- 真实手机安装、再次点击灰色按钮、两个平台分别计数、厂商安全扫描和应用内更新仍是独立验收项；云端构建成功不能替代真机已安装/已运行证据。

## 2026-08-19 Android 0.6.25 / iPhone 0.6.12 按钮层级优化候选

- 两端作品卡片的“预览 / 发抖音 / 发小红书”改为左对齐的纵向紧凑按钮，按文案自适应宽度；预览为描边样式，平台入口为主按钮。预览、分享、计数和隐私边界逻辑不变。
- 版本：Android `versionCode=63` / `versionName=0.6.25`；iPhone `0.6.12/build 31`。本地 Windows 不执行 Android Gradle/Xcode 构建，必须以 GitHub Actions 为云端构建证据。
- 真实手机安装、厂商安全扫描和点更新后的系统安装仍是独立验收项；构建或公开链接不能替代手机已安装/已运行证据。

## 2026-08-18 Android 0.6.24 / iPhone 0.6.11 隐私与三按钮预览候选

- 两端删除自动截图采集、截图中转和自动读取/同步系统剪切板；iOS 同时删除 `ClipboardBridge`、`/v2/clipboard`、剪切板设置开关和相关前后台生命周期调用。用户主动复制文案/诊断信息仍保留。
- Android 作品卡片改为“预览 / 发抖音 / 发小红书”一行三按钮，大图增加上一张/下一张；iPhone 使用同样的三按钮，图片预览支持左右滑动。
- 版本：Android `versionCode=62` / `versionName=0.6.24`；iPhone `0.6.11/build 30`。本地 Windows 不执行 Android Gradle/Xcode 构建，必须以 GitHub Actions 为云端构建证据。
- 真实手机安装、厂商安全扫描和点更新后的系统安装仍是独立验收项；构建或公开链接不能替代手机已安装/已运行证据。

## 2026-08-18 iPhone 0.6.10 移除截图链

- iOS 已删除 `ScreenshotMonitor`、截图设置入口、截图接收开关和前台截图轮询。
- `project.yml` 不再声明 `NSPhotoLibraryUsageDescription`；普通文件、图片导入、系统分享和局域网传送保持不变。
- 版本升至 0.6.10/build 29；需以 GitHub Actions 的 iOS IPA 结构、版本和 SHA-256 校验为准。

## 2026-08-18 Android 0.6.23 剪切板/截图模块移除候选

- 根因：旧版 `OnlineService` 在设备发现/服务启动时维护悬浮剪切板，读取并写回系统剪切板，并通过 `MediaStore` 观察截图；Manifest 同时声明 `SYSTEM_ALERT_WINDOW`，这正是用户看到隐私或风险提示的可疑行为链。
- 修复：删除 `ClipboardActivity`、剪切板存储/同步、中继元数据、截图检测/接收器、悬浮窗 UI 与设置入口；`OnlineService` 不再执行自动剪切板读写、截图观察或截图中转，`/v2/clipboard` 返回 404；普通文件传输、作品导入、更新包接收和设备发现保留。
- 权限：移除 `SYSTEM_ALERT_WINDOW`；`READ_MEDIA_IMAGES` 等媒体权限仍用于正常的作品/文件选择与导入，不能据此宣称应用完全零媒体权限。
- 版本：Android `versionCode=61` / `versionName=0.6.23`，本地构建候选，未发布、未安装。
- 验收：先跑 `:app:testDebugUnitTest :app:assembleRelease :app:lintRelease`，再检查 Release Manifest/权限和真实设备安全扫描；重点确认没有悬浮窗权限、没有自动剪切板访问、没有截图通知/接收器。

## 2026-08-18 iPhone 0.6.9 分类库存统一候选

- 现场发现：苹果12在线且总作品数 16，但 `/v2/info.workCounts` 为空，电脑无法判断精准流量库存。
- 修复：iPhone 在作品扫描完成后持久化 `total/conversion/traffic/uncategorized`，`/v2/info` 上报 `workCounts`；版本升至 0.6.9/build 28。
- Windows 自动补货只使用 `conversion` 精准流量字段，字段缺失保持未知并跳过自动发送；精准来源目录只接受 `[转]`/`【转】` 标记。
- 本地 Windows 无 Xcode，未完成 iOS 编译或真机覆盖安装；需云端构建后让苹果12重新安装并刷新作品库，再实读 `workCounts`。

## 2026-08-17 Android 0.6.22 手动下载入口候选

- Android `versionCode=60` / `versionName=0.6.22`：设置 → 软件说明底部增加可点击的公开安装包发布页。
- 点击入口只使用系统浏览器打开 `https://github.com/zwmopen/gallery-updates/releases`，用户自行选择 APK 下载；不静默安装、不改变应用内校验和安装流程。
- 发布页地址复用 `UpdateEndpoint.RELEASE_PAGE`，与应用内更新检查使用同一公开仓库；本机已通过 `:app:testDebugUnitTest`、`:app:assembleRelease` 和 `:app:lintRelease`，尚未发布或安装到真机。

## 2026-08-14 Android 0.6.21 大文件断点续传候选

- Android `versionCode=59` / `versionName=0.6.21`：接收端新增可恢复任务清单、`GET /v2/tasks/{taskId}` 和按 `X-File-Offset` 追加上传。
- 电脑端 `device-folder-transfer` 为同一设备、文件名和 SHA-256 生成稳定任务 ID；传输失败时保留本地可恢复账本，下一次先查询手机断点再续传。
- 手机临时任务保留 30 分钟无活动；只有明确调用 `/cancel` 才会删除未完成任务。完整文件仍须 SHA-256 校验后才允许 `/commit`，避免半份文件进入作品库。
- 旧接收端没有状态查询接口时，电脑端退回旧的从头传输模式；这不代表旧手机具备断点能力。
- 已验证：Android `:app:testDebugUnitTest`（Gradle 9.4.1）通过；Python 传输技能 32 项单测、语法检查通过。
- 尚未宣称真机完成：VIVO 当前实装仍为 0.6.20/code 58；下一步构建并安装 0.6.21/code 59，制造一次受控中断，再验证状态查询、偏移续传、最终哈希和提交。

## 2026-08-14 文件传输第一批稳定性修复

- 文件传输技能的局域网发现改为“UDP 优先、失败汇总”，不再把每个无响应 IP 写成 DEBUG 日志；日志单文件达到 8 MB 后滚动并保留 3 份，传输日志更适合人读和故障定位。
- Android `OnlineService` 每分钟检查接收任务；任务未收齐且连续 5 分钟没有活动时，自动删除 `cache/share/<taskId>` 临时目录、恢复 `online`，并记录“传输中断已清理”。已收齐文件的提交阶段不会被该清理打断。
- Windows WPD USB 传送先把顶层项目写入 `.相册传送-<批次>-<序号>` 临时名称，完整写入后才改为正式名称；中途失败会尝试删除临时对象。USB 已经开始写入但未完成时不再自动改用 Wi-Fi，避免重复传送。
- USB 改名提交阶段会记录已提交对象；后续改名失败或用户取消时，临时对象和已提交对象都会按对象 ID 回滚，避免多项目只完成一半。普通发送在 UDP 找到设备后不再继续扫描 254 个地址。
- Python LAN 发送会在成功提交后记录“设备 + 文件名 + SHA-256”指纹；同一内容再次发送会在创建手机任务前拒绝。运行账本位于 `D:\AICode\运行数据\device-share-hub\transfer-ledger`，不写进技能源码目录。
- 已验证：Python 传输技能 27 项单测和语法检查通过。缓存的 Gradle 可以启动，但因缺少 Android SDK 未进入安卓测试；当前机器没有 MSVC/CMake，因此 Windows 原生编译未执行；USB 真机成功、断线清理和跨通道回退仍需连接设备后验收。

## 2026-08-14 Windows 4.3.6 / Android 0.6.20 双通道更新候选

- 新增桌面 `相册_USB一键复制.cmd`，实际逻辑唯一保存在 `scripts/copy-usb-apk.ps1`：检查 USB 授权设备、读取本地最新 APK，将文件复制到手机 `Download` 文件夹；同名文件 SHA-256 相同就跳过，复制后再次校验；没有设备、未授权或多设备未选择时停止，不安装应用、不修改手机应用版本和数据。
- Android `versionCode=58` / `versionName=0.6.20` 在发现信标和 `/v2/info` 中上报 `appVersion`、`versionCode`、`workCount`、分类库存及 `apk-push-v1` 能力。
- Windows 设备卡读取并显示手机版本、精准库存和泛库存；旧信标仍可发现，但不会被电脑盲目自动推送 APK。
- Windows 从“设置 → 发送更新包”选择带版本号的 APK 后，保存到 `%LOCALAPPDATA%\\ZwmDeviceShareHub\\mobile-updates`；自动更新只使用其中版本最高的 APK。
- 源码推送后由 GitHub Actions 云端构建；构建通过后发布版本化 APK、SHA-256 和 `latest.json`。Windows 不做定时轮询，只在低版本手机在线且本地没有候选包时按需取回已发布 APK。
- 手机上线且版本较低时，电脑只通过 Wi-Fi LAN V2 发送一个独立 APK 任务；手机仍执行 SHA-256、包名、版本码和签名校验，并由用户确认系统安装。电脑不会静默安装、不会改变作品账本。
- GitHub 是手机首选更新通道，电脑缓存推送是 GitHub 检查失败时的备用通道。当前代码已完成，仍需 Windows 编译、Android 单测/Release/Lint，并用 VIVO 真实验收“打开 → 上报 → 电脑触发 → 手机安装 → 重新上线”。

# 2026-08-13 Android 0.6.19 即时库存信标候选

- `versionCode=57` / `versionName=0.6.19` 已完成 Debug/Release 构建、单测和 Lint。Release APK 仍是本地候选，不代表已发布或已安装到 VIVO。
- `OnlineService.publishWorkInventory()` 在作品库刷新后请求一次即时状态信标；发现线程仍保持 2.5 秒周期信标，电脑端轮询与分类库存未知保护不变。这样手机刷新库存后无需等待下一轮广播。
- 当前现场证据：VIVO 已在线但 `/v2/info` 仍返回 `workCounts=null`、`workCount=0`，因此工作台按规则记录 `inventory_unknown`，不会把总数当精准库存而误发。安装候选并重新打开接收端后，必须实读 `workCounts.conversion/traffic` 才能验收自动补货。
- 验收顺序：先手动发送确认 VIVO 可达，再刷新手机作品库并确认分类库存，最后在低于阈值时观察自动补货；失败仍按 3 次暂停、重连再试。

# 2026-08-13 Android 0.6.18 旧版 Android 启动兼容候选

- 实机根因：Redmi Note 8（Android 10 / API 29）安装 0.6.17 时 `pm install -r -d` 返回 `Success`，包名、最低 API、版本签名均兼容；但首次启动在 `MainActivity.onResume()` 通过普通 `startService(ACTION_REFRESH_OVERLAY)` 启动 `OnlineService`，被 Android 10 的后台服务限制拒绝，抛出 `IllegalStateException`，表现为“更新后打不开/像解析失败”。
- 修复：`MainActivity` 的浮层刷新改为 Android O+ 使用 `startForegroundService`，并捕获系统拒绝/安全异常写入诊断日志，避免启动异常使主界面崩溃。`OnlineService.onStartCommand()` 已在处理命令前调用 `startForeground()`，无需改传输协议或数据目录。
- 候选版本：Android `0.6.18` / versionCode `56`，包名仍为 `com.zwm.gallery`，minSdk `26`、targetSdk `36`，签名链未变。
- 验证：Android 单元测试、Release 构建、Release Lint 全部通过；在真实 Redmi Note 8（serial `8d90da66`）覆盖安装成功，版本实读 `0.6.18/code 56`，启动后进程保持运行且无 `AndroidRuntime`/`FATAL EXCEPTION`，原有应用数据未清理。
- 当前状态：0.6.18 目前是本地实机验证候选，尚未替换公开 `gallery-updates` Release；发布前需将 APK、SHA256SUMS 和 `latest.json` 一起更新，并再次验证应用内下载→校验→系统安装链。

# 文件收发中控 V4.3.4 维护交接

## 2026-08-10 Android 0.6.17 分类库存候选

- 根因：旧 `/v2/info` 只有总作品数，团建工作台选择“精准流量”时无法知道手机实际只有 8 个精准作品。
- 修复：增加可选 `workCounts` 聚合字段，保留 `workCount` 向后兼容；分类缺失时调用方必须按未知处理。
- 当前仅为源码候选，需完成全量 Android 测试、Release/Lint、同签名覆盖安装和 K60 `/v2/info` 实读后再标记正式发布。

## 2026-08-08 独立设置中心与交付状态

- Windows 源码已升级为 V4.3.4：右上角“设置”改为独立窗口，并统一收纳原始目录、归档目录、版本更新、更新包投送、自动补货阈值、开机自启、暗色模式、诊断日志与软件说明；主界面移除重复低频入口。更新检测遇到 GitHub API 限流时自动改用 Release 最新页跳转。
- iOS 源码升级为 0.6.8/build 27，设置、回收站等二级页面的返回按钮统一为中文“返回”，应用标识和现有数据结构不变。
- 远程中继已固定升级到 `wrangler 4.120.0` 与 `@cloudflare/workers-types 5.20260808.1`；本机 `npm audit` 为 0 漏洞，语法、类型、10 项测试和 Wrangler dry-run 均通过。升级后的 workerd 对请求流生命周期更严格，R2 上传已改用 `FixedLengthStream` 并等待输入、存储两端完成，避免响应返回后仍读取请求体。
- GitHub Actions run `31235217862` 已全绿：Windows MSVC/CTest、Android 测试/Release/Lint、iOS 真机 SDK 构建与包内版本校验、远程中继审计/类型/协议/HTTP 烟测全部通过。
- 正式 Release 已发布：`device-share-hub-v4.3.4-2026.08.08`，包含 Windows V4.3.4、iPhone 0.6.8/build 27、Android 0.6.16 和中文说明。Windows SHA-256 `967AC115FB24A43BC284AC1E51E4C82A44D25E1E4A7542083DBABF6A11C916C9`；IPA SHA-256 `520AFD3AACE6A7352ABA59DA7C30E17D05F1B2980FE8E66D73C20438F953C7A9`。
- 桌面旧 V4.3.2、V4.3.3 已移入回收站，当前只有 `文件收发中控-Windows-V4.3.4.exe`；已真实打开独立设置窗口，并在 GitHub API 限流状态下验证 Release 跳转兜底返回“当前已是最新版本”。iPhone 实体仍是 0.6.7/build 26，0.6.8 只证明构建与发布完成，未在没有用户签名授权时冒充已安装。

## 2026-08-08 上帝视角审查

- 三端最近一次 Actions run `31195207671` 中，Windows、Android、iOS 构建均成功；唯一失败项是 `remote-relay-check`。
- 中继失败的直接原因是 `remote-relay` 依赖链中的 `wrangler 4.114.0 → miniflare → undici`：`npm audit --audit-level=high` 报告 3 个漏洞，其中 1 个 high，修复建议升级 Wrangler 至 `4.120.0` 或更高版本。中继发布前必须升级依赖并重跑审计、类型检查和 HTTP 烟测。
- 本机远程中继目录当前没有安装 `node_modules`：协议语法检查和 10 项 Node 测试可通过，但 `npm run typecheck` 在本机因找不到 `tsc` 未执行成功；不能把本机结果描述为类型检查通过。
- Android 0.6.16 已实际发布到 `zwmopen/gallery-updates` 的 `v0.6.16`，包含 APK 与 `SHA256SUMS.txt`；本交接文档前面的 2026-08-07 记录仍写着 v0.6.14，属于过期事实，后续维护应以 GitHub Release API 和实际资产为准。
- iOS 中文“返回”按钮修复已在本机提交 `15bc8e5`，但当前 `main` 比 `origin/main` 超前 1 个提交，尚未推送，也没有新的 IPA 发布；现有 iPhone 仍不能视为已安装该修复。
- 桌面上的 Windows V4.3.2 候选包存在且三端构建曾成功，但当前没有运行中的中控进程；正式交付前仍需重新启动桌面包做一次设置、设备发现、发送和接收回归。

## 2026-08-07 实机安装与 Wi-Fi 传输验收

- Windows 最新候选包 `文件收发中控-Windows-V4.3.2.exe` 已放置到桌面并启动，窗口标题为 `文件收发中控 V4.3.2`；旧版 V4.1.1 已移入回收站，不作为日常运行版本。
- Redmi Note 8 已通过 ADB 覆盖安装当前 Android 候选包，实读 `versionName=0.6.16`、`versionCode=54`；安装后仍保留原应用数据与目录授权。
- iPhone 12 在线实读 `appVersion=0.6.7`、`build=26`，与当前 iOS 构建产物一致；Windows 端没有在未授权 Apple ID 的情况下静默重签 IPA。
- 局域网发现同时找到 Redmi Note 8 与 iPhone 12，随后各完成一次 140 字节校验文件的 Wi-Fi 实际提交：Android 返回 `committed=true`；iOS 返回 `ok=true, received=1`。两次均经过 SHA-256 计算、HTTP 上传和接收端确认。
- 当前 `gallery-updates` 正式 Release 仍是 v0.6.14，Android 0.6.16 / Windows V4.3.2 属于当前主仓库 CI 候选包，尚未创建新的正式 Release；对外更新通道不能把候选包描述成已发布版本。
- 本次校验只使用临时文件，没有改动用户素材库；临时文件保留在设备接收目录中，后续可从文件浏览器删除。

## 2026-08-07 通用设置与版本检查

- Windows 主窗口右上角新增“设置”按钮，集中管理原始目录（收发文件根目录）、GitHub 版本检查、补货/更新包功能和软件介绍。
- 原始目录仍复用数据库 `library_path`，同时驱动左侧文件库浏览、电脑接收落盘和手机/其他设备发送；旧版配置保持兼容。
- Windows 版本检查调用 GitHub 公开 Release API，默认启动后延迟检查一次并每 6 小时复查；只提示并打开发布页面，不静默替换 EXE。网络不可用时手动检查显示可行动错误。
- 本轮源码版本为 Windows V4.3.2；Actions run `31189387373` 的 Windows、Android、iOS 构建均成功，Windows artifact 已核对为 `文件收发中控-Windows-V4.3.2.exe`。该提交尚未创建正式 Release。
- 同一 run 的 `remote-relay-check` 仍因现有 `wrangler → miniflare → undici` 依赖审计（3 个漏洞，含 1 个 high）失败；这不影响三端编译，但在中继发布前必须单独升级依赖并重跑审计。
- 用户对产品形态的长期要求：通用软件的设置能力应进入独立设置窗口，按目录、更新、功能、说明和诊断分组；本版本右上角弹出菜单是过渡入口，后续扩展设置时应迁移为真正设置窗口，不继续堆叠菜单项。

## 2026-08-07 本轮开发交接

- Android 已恢复应用内 HTTPS 断点更新链：`AppUpdateService`、`UpdatePackageValidator`、`UpdateInstallActivity`。系统安装仍由用户确认，应用不会静默安装。
- Windows 已在主窗口底部加入“功能设置”，设置保存到现有 `ContentStore`：`auto_restock_enabled`、`auto_restock_threshold`。自动补货只选择包含文本帖子和图片的作品文件夹，并且只对在线、已上报作品数且低于阈值的设备触发。
- 更新包投送复用既有 `UploadToDevice`，不新增 IP/ADB/Root 配置；Android 收到 APK 后会校验包名、版本码和签名，合格包进入私有更新目录，安装动作仍由用户确认。
- Android `testDebugUnitTest assembleRelease` 已通过（66 项单元测试）；已用一台真实 Android 11 旧版手机验证 0.6.16 APK 的 LAN/V2 传输和接收 commit。本机没有 Visual Studio/CMake 工具链，Windows EXE 构建、Android 手机上的实际覆盖安装和第二台手机验收待后续现场连接完成。

## 2026-08-02 Windows 右键入口误复制修复

- V4.2.0 把设备快捷方式放在 `SendTo\相册在线设备` 子文件夹中；Windows 不把这里当级联菜单，而把文件夹本身当复制目的地。实体操作因此把一个 45,646,452 字节视频复制进该目录。
- 已核对误复制副本与原件 SHA-256 完全一致，只把副本移入回收站，原件保留。V4.2.1 改为 `SendTo` 根目录单一快捷方式“发送到相册设备”，点击后由主进程弹出实时在线设备选择菜单。
- 新流程直接读取用户选择的原路径，经既有 V2/USB 传送；不建立长期副本。旧子目录迁移只删除自有 `.lnk` 和 marker，若出现未知文件则保留，禁止静默删除用户内容。
- 右键发送不再调用重复传送历史检查；设备选择后主窗口显示真实进度，完成或失败再弹结果。设置项统一为“发送根目录”，发送树和 Windows 接收端共用该路径；左侧以最多 12 层、5000 个可见节点的惰性展开列表浏览，使用 canonical visited 集合防止 Junction/重解析点循环。
- 右键发送的反馈改为不抢焦点的短文字浮层：开始、传送状态和完成/失败结果显示在屏幕右下角，几秒后自动消失，不保留常驻进度小窗。Android 0.6.15 接收端始终显示静默进度通知和完成结果通知，“声音提醒”只控制声音。
- 右键 SendTo 调整为后台入口：快捷方式持久保留，首次调用可静默启动中控的发现/接收进程，已运行实例不恢复或前置主窗口；仅弹出设备选择菜单与短文字提示。Windows 系统自带“发送到 → 桌面”不属于本软件设备传送。
- 应用内“选择在线设备”发送也进入同一反馈链，发送文件或文件夹时不再只更新隐藏的主窗口状态。

## 2026-08-02 Android 0.6.15 接收进度通知

- Android 0.6.15/code 53：接收文件期间使用静默系统通知显示整体百分比和文件序号；全部提交后撤销进度通知并显示“已收到多少文件、识别多少作品、耗时多久”的结果通知。
- 声音通知开关不再关闭通知本身，关闭时改用低打扰静默通知；失败也保留可见失败结果。Android 本机测试、Release 构建、Lint 已通过，实体通知显示仍需在用户连接的红米/华为设备上复核。

## 2026-08-02 三端交付构建与发布

- 主账号 run `30736561129` 的 Windows、Android、iPhone 均成功；`remote-relay-check` 为瞬态本地 Worker/R2 闭环失败，不影响三端编译。
- 按备用额度规则将提交 `919d9cd` 同步到 `rpgzwm/team-video-workflow-build` 和 `idmzwm-sys/team-video-workflow-build`。备用 `idmzwm-sys` run `30736760595` 的 Windows、Android、iPhone、remote-relay 四项全部成功，作为本次交付构建依据。
- 公开 Release [`gallery-updates v0.6.14`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.14) 已替换为 Android 0.6.15、Windows V4.2.1、iPhone 0.6.7 资产；`latest.json` 已指向 Android versionCode 53。最终 APK SHA-256 `9262270E696D293E7C9AB9030D5B5917546894508BAC7DD722B48BF69E2A4037`，Windows EXE `4FDBFBD08909364B433C40E68EB52C8FD0DF9B2B9CCC150A5224873B3A57BFA2`，iPhone IPA `D015EE8DCF6555B9C9BACF728375045CB2F52E73EC7B3C7F9CEAC0D2CB600917`。
- 三个公开安装包和 SHA256SUMS 已重新下载核对一致；新源码快照为 `team-video-workflow-source-9ff62f2.zip`。Android 真机安装、系统通知权限和后台接收仍需用户连接红米/华为后逐台复核。

## 2026-08-02 Windows 右键“发送到”在线设备候选

- 已正式更新 [`gallery-updates v0.6.14`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.14)：Windows 资产替换为 `Album-Windows-V4.2.0.exe`，SHA-256 为 `E915FE56DE9626ACFB13C7DDC8A93033076560EA1AB1627994C99D597FCC0D27`，公网重新下载核对一致；源码快照为提交 `76f2023d`。最终 Actions run `30733830138` 的 Windows、Android、iOS 和 remote relay 四项全部成功。
- 实体回归补充：最初主动发现因未请求 Windows 网关信息得到 `targets=0`；补齐 `GAA_FLAG_INCLUDE_GATEWAYS` 后改用带连接截止时间的原生 LAN socket 探测，最终日志为 `targets=253 devices=1`，真实生成 `1号｜公司｜红米13.lnk`。同一时间共享传送脚本也发现该手机，菜单和协议发现结果一致。
- 右键传送闭环：合成 protocol 2 接收端完成“设备快捷方式参数 → 第二进程 → `WM_COPYDATA` → 主实例 → 既有上传协议 → commit”，35 字节源文件与接收文件 SHA-256 完全一致；停止合成端并等待 35 秒后测试快捷方式被自动移除，真实在线手机项保留。

- Windows V4.2.0；Android 保持 0.6.14/code 52，iPhone 保持 0.6.7/build 26。Windows 中控运行时动态创建当前用户“发送到”目录下的自有子菜单“相册在线设备”，只列出 35 秒内仍能主动确认或 USB 通道可用的设备。
- 实体环境确认共享技能可主动探测到在线手机，而新启动中控收不到 UDP 回包；根因是部分路由器抑制 Wi‑Fi 广播回复。Windows 因此复用已验证思路，以原生 WinHTTP 并发探测默认网关所在私有 `/24` 的 `/v2/info`，每 15 秒兜底一次，不依赖 Python 或共享技能运行时。
- 每个设备快捷方式仍以当前 EXE 为目标，参数只保存编码设备 ID；资源管理器追加用户选择的文件/文件夹路径。第二进程通过 `WM_COPYDATA` 把请求交给现有单实例窗口，主进程再次校验路径、数量、目标和在线状态后调用原 `UploadToDevice`。
- 菜单目录使用所有权标记；只清理目录内由中控生成的 `.lnk`，遇到无标记同名目录拒绝覆盖。中控退出时移除自身子菜单，不修改用户其他 SendTo 项，不注册管理员级 Shell Extension。
- 自动测试覆盖 Unicode 设备 ID 编解码、无效编码、包含中文/空格路径的 IPC 序列化、损坏载荷拒绝。仍需 Windows CI 编译/测试及实体资源管理器单文件、多选、文件夹、设备离线、进程退出回归后才能正式发布。

## 2026-08-01 Android 0.6.14 正式发布

- 正式 Release：[`gallery-updates v0.6.14`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.14)；更新索引提交 `c89df9d` 已指向 Android 0.6.14/code 52。
- 公网 APK 重新下载后 SHA-256 为 `583349684332EF771C08108B09A1F0CEDABB99EB0E226C10A02440B08FADDD03`，与 `latest.json` 和 Release 内 `SHA256SUMS.txt` 一致；包名 `com.zwm.gallery`、v2 签名证书和原升级链一致，清单不含 `REQUEST_INSTALL_PACKAGES`。
- 最终 CI run [`30705203161`](https://github.com/zwmopen/team-video-workflow/actions/runs/30705203161) 的 Android、iPhone、Windows 构建成功；`remote-relay-check` 首次遇到 Wrangler 本地瞬态故障，失败任务重跑后完整通过，因此最终四项 CI 全部成功。
- Android 真机已覆盖安装 0.6.14/code 52，并完成 RNDIS USB 网络共享下的手机→电脑和电脑→手机双向实传，接收文件 SHA-256 与源文件一致。公开更新索引已经发布，但仍保留“从旧版应用内确认下载→系统通知点击安装”的完整实体回归项，不能用 ADB 覆盖结果替代。

## 2026-08-01 Android 系统下载更新 0.6.14 候选

- Android 0.6.14/code 52；iPhone 保持 0.6.7/build 26，Windows 保持 V4.1.2。只改变 Android 更新交互，不回退其他功能或数据结构。
- 每次启动自动检查仍保留；发现新版后先弹窗，用户点击“系统下载”才创建 DownloadManager 任务。下载完成后由用户点击系统完成通知进入安装。
- 删除 `REQUEST_INSTALL_PACKAGES`、`FOREGROUND_SERVICE_DATA_SYNC`、`AppUpdateService`、`UpdateInstallActivity`、私有 APK 复制与应用安装通知。相册不再出现“允许来自此来源”授权页，也不主动打开系统安装器。
- `UpdateDownloadReceiver` 只接受与本机记录任务 ID 相同的系统完成事件，后台核对 Release 清单中的 SHA-256；不一致时删除系统下载并在下次进入应用时提示。接收器不提供安装入口。
- APK 目标名固定为 `album-Android-0.6.14.apk`，位于系统 Downloads；更新发现继续使用 GitHub Release API 与同一 Release 的 `SHA256SUMS.txt`。
- 本机 20 个测试套件共 66 项全部通过，Release 构建与 Release Lint 通过；候选 APK 为 0.6.14/code 52，v2 签名证书 SHA-256 保持 `CAC54653FDFDBD19E0D9952FECA70E9B2A0530CE6676EB17A3EC24A56BE1848B`，清单不含安装来源权限或 Debug 标志。系统下载和系统通知点击仍需 Android 真机联网复核，不能以自动测试代替。
- 同一候选继续补齐手机→电脑 USB：传送页增加可记忆的自动/USB/Wi‑Fi筛选；USB 模式打开系统 tether 设置，经 `rndis0/usb0/ncm/tether` 接口与对端同子网时把电脑标为 USB。普通 MTP 仍只是 Windows 主机写手机，反向必须使用 USB 网络共享，不引入 ADB 或自定义驱动。
- 2026-08-01 接入手机起点为 Android 0.6.13/code 51；手机 Wi‑Fi 为 `192.168.0.0/24`，电脑 Wi‑Fi 为 `10.40.243.0/24`，且最初没有 RNDIS/NCM 网卡，所以旧版看不到电脑符合网络拓扑。设备重连后已用原签名覆盖 0.6.14/code 52，设置和数据保留。
- 真机开启 USB 网络共享后，手机出现 `rndis0 / 10.123.109.0/24`，Windows 出现 Remote NDIS 网卡；Android 传送页真实显示 `ZWM / Windows PC · USB`。手机→电脑 48 字节测试文件落到 Windows `Downloads/相册收件箱`，电脑→手机落到 `Download/Lark/接收-task-*`，两方向 SHA-256 均与源文件一致。测试副本随后精确清理。
- 真机还确认传送方式弹窗包含自动、USB、Wi‑Fi并可记忆；USB 未发现页和设置按钮完成界面检查。最终完成状态改为按 `peer.transport` 显示，避免 USB 路径仍写“WiFi 传送完成”。系统下载更新链仍需从旧版通过公开 0.6.14 索引再验，不能用本次 ADB 覆盖代替。

## 2026-08-01 Android 确认式更新 0.6.13 候选

- Android 0.6.13/code 51；iPhone 保持 0.6.7/build 26，Windows 保持 V4.1.2。修正版 0.6.11/code 49 仅作为实体升级起点；0.6.12/code 50 是首轮应用自有下载的过渡发布。
- 每次启动仍自动检查版本，但发现新版后先弹窗，由用户点击“下载更新”；完成 SHA-256、包名、版本和签名校验后，再弹包含版本、文件名和大小的“安装”按钮。
- 删除下载完成广播直接启动安装 Activity 的路径，规避 Android/MIUI 后台界面启动限制；首页不在前台时使用常驻通知，下次返回首页再次提示。
- Redmi 公网回归先捕获到 `DOWNLOAD_COMPLETE` 外部 UID 投递限制；开放接收器后又确认 MIUI 将 GitHub 下载交给迅雷内核并返回状态 700。新版不再把用户确认后的 APK 交给系统 `DownloadManager`，而是启动前台 `dataSync` 服务自行执行 HTTPS 下载、有限跳转、断点续传和持续通知。
- 下载临时文件保存在应用私有 `files/updates/*.apk.part`；网络失败保留以便续传，校验失败立即删除。通过 SHA-256、APK 解析、包名、版本码和签名校验后才改名为 `.apk`，并广播首页显示“安装”按钮。
- Redmi 0.6.11 → 0.6.12 公网实测没有再出现迅雷/系统下载提示，应用通知持续更新进度并在约 24 秒后下载完；但 MIUI 的 `PackageManager` 对 v2 APK 返回空 `SigningInfo`，校验器误报“安装包没有签名”。0.6.13 同时请求 `GET_SIGNING_CERTIFICATES` 与兼容 `GET_SIGNATURES`，新版字段为空时回退旧字段。
- 同机移动网络连续访问 raw 更新索引超时，更新发现改为 GitHub Release API，并从该 Release 的 `SHA256SUMS.txt` 精确选择 APK 哈希；文件名必须完全匹配，避免拿错资产。
- 后台文件传送继续由 `OnlineService` 负责，首页可见状态仍保留给传送和截图提示，不与更新下载混用。
- 首轮 0.6.12 本机 20 个测试套件共 65 项单元测试、Release 构建与 Lint 通过，CI 四项全部通过；公开 APK SHA-256 为 `DF5D639DABC3BA0F1781225D34BFA65BC92C1D31B6093414198557684F623F85`。0.6.13 增加 Release 清单精确匹配测试后，本机 20 个套件共 66 项测试、Release 构建与 Lint 通过；候选 APK 810402 字节，SHA-256 `A4EE7937D1E376BCE26C9FC29B3093DE4879C991ACE2840B484FA3D98E7F4869`。红米在安装诊断版后 ADB 断开，最终签名回退与“安装”点击进入系统页必须在设备重新连接后补验。
- 0.6.13 源码提交 `8e62fa5`；Actions run `30694602779` 的 Android、iPhone、Windows 已通过，远程中继首次瞬态失败后已发起失败任务重跑。公开 Release 为 [`gallery-updates v0.6.13`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.13)，更新索引提交 `d62589e` 已指向 0.6.13/code 51。公网重新下载 APK 为 810226 字节，SHA-256 `F00835EC3E5817232C4C652B9732E1D1513F33ACEB4D0BE29D8798112347867B`，与索引和 `SHA256SUMS.txt` 一致；v2 签名证书保持原升级链。

## 2026-08-01 Android 真机交互修复 0.6.8 正式版

- Android 0.6.8/code 46；iPhone 保持 0.6.7/build 26，Windows 保持 V4.1.2。
- Redmi Note 8 已沿 `com.zwm.gallery` 和原签名覆盖安装，设备实际读取为 0.6.8/code 46。首页确认文件夹入口位于左侧、四分类文字不再被白色滑块遮挡；点击“泛流量”后滑块与文字位置正确。
- 真机录屏确认下拉刷新完整出现“下拉刷新 → 松开刷新 → 正在刷新作品… → 已刷新，共 N 个”以及旋转进度，结束后内容回弹归位。
- 设置页开关不再调用厂商 `Switch` 外观，改由应用完整绘制 52×32dp 胶囊轨道、白色圆点、柔和阴影与 180ms 动画；Redmi 真机确认开/关轨道完整，说明小字可换行。
- 截图设置增加“截图主设备”选择行。真机确认打开后会显示“不选择主设备”和当前在线可信设备状态；没有其他设备在线时明确禁用对应项。
- 更新包校验完成后，若首页仍在前台则直接启动 `UpdateInstallActivity` 并进入系统安装界面；若应用已在后台，Android 后台启动限制下保留完成通知，用户点通知即可继续。应用不申请 `REQUEST_INSTALL_PACKAGES`。
- 源码提交 `a98db67` 的 GitHub Actions run `30690888591` 已全部通过：Android 21 个测试套件共 65 项单元测试、Release/Lint、iPhone 真机 SDK 构建、Windows 构建与协议闭环均成功。
- 正式安装包已发布到 [`gallery-updates v0.6.8`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.8)，更新索引提交 `9f1c0f9`。云端 APK 为 804830 字节，SHA-256 `3F74BA286C93F7BFEA25F2879551F0F9C52AF142206C35B6966FD5E319E9E27A`；公网重新下载与索引哈希一致，并再次同签名覆盖安装到 Redmi Note 8。

## 2026-08-01 下拉刷新收口 0.6.7 正式版

- Android 0.6.7/code 45 与 iPhone 0.6.7/build 26 删除首页顶部刷新按钮，作品和文件模式统一使用下拉刷新；Android 回收站仍显示必要返回入口。
- 源码提交 `bffd295` 的 GitHub Actions run `30685974568` 已全部通过：Android Release、iPhone 真机 SDK 构建、Windows 与远程协议闭环均成功。Android 本机单元测试、Release 构建和 Release Lint 同样通过。
- 正式安装包已发布到 [`gallery-updates v0.6.7`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.7)。更新索引提交 `a280878` 已指向 Android 0.6.7/code 45 与 iPhone 0.6.7/build 26；APK 为 799370 字节、SHA-256 `3049B536D7F70F2A9B79CA50320FF986D633ADC53AFF966A79A4AFDFDF4B1294`，IPA 为 17208963 字节、SHA-256 `0B5058BB570CF4C0967EADFE1E9740122D5028BC96EBE7FAD4BD3F107FE43D01`。
- iPhone 0.6.7/build 26 已在 iPhone 12 / iOS 26.5.2 沿原应用标识完成签名覆盖安装；Sideloadly 的签名、打包、上传和系统安装最终显示 `Done / 100%`，设备应用清单真实读取到 0.6.7/build 26，直接启动成功（PID 4346）。设置页截图确认新版剪切板/截图控制与版本号；返回首页后的真机截图确认顶部刷新按钮和重复“作品/总数”均已删除，保留文件夹、传送、回收站和设置，并显示“全部 16、转化 11、泛流量 0、未分类 5”。分类切换动画与下拉刷新手感仍需触屏操作，不用静态截图代替动态验收。

## 2026-08-01 双端原生交互统一 0.6.6 候选

- Android 0.6.6/code 44 与 iPhone 0.6.6/build 25 同步升级；Windows 保持 V4.1.2。
- 双端首页删除重复的“作品 + 总数”，分类控件承担数量展示。iPhone 增加真实文件浏览入口、双箭头刷新图标；Android 增加连续分类滑块、内容过渡、边缘阻尼回弹和下拉刷新，并统一苹果式浅色层级。
- iPhone 设置删除早期“工作方式、导入、使用我的 iPhone/相册”，改为标题 + 下方浅色说明；新增前台剪切板同步、前台截图识别、自动发送、主设备选择和主设备接收。iOS 不提供系统级悬浮球或后台永久读取。
- Android 本机单元测试、Release 构建和 Release Lint 已在改版源码上通过。iPhone 0.6.5/build 24 已在 iPhone 12 / iOS 26.5.2 完成同标识覆盖安装、签名验证、直接启动和设置页截图；0.6.6 仍须 macOS CI 构建后重新侧载并实体操作，不得把 0.6.5 的结果算作新版通过。

## 2026-08-01 双端分类与自动整理 0.6.5 候选

- Android 0.6.5/code 43 与 iPhone 0.6.5/build 24 同步升级；Windows 没有代码变化，保持 V4.1.2。
- iPhone 首页四分类增加数量并修复筛选后卡片索引错误；Android 和 iPhone 自动整理统一为紧凑设置行、常用预设与 0～720 小时自定义输入。
- Redmi Note 8 已由正式 0.6.4 同签名覆盖安装 0.6.5 候选版，原有 1 个作品、Lark 目录和设备名称均保留；四分类、紧凑设置行、预设弹窗、即时保存、130% 系统字体及恢复 1 小时默认值完成实体操作。开关卡片的标题与浅色小字说明已分层并改为内容自适应高度。
- Android 本机单元测试、Release 构建和 Release Lint 通过。iPhone 必须以 macOS CI 编译及用户重新连接的实体设备侧载为准，未连接前不标记真机通过。
- 最终源码提交 `ccee89a` 的 GitHub Actions run `30683557353` 已在重跑瞬时 Wrangler 本地服务故障后全部通过：Android、iPhone、Windows 与协议闭环均成功。公开 Release 为 [`gallery-updates v0.6.5`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.5)。
- 更新索引提交 `a83a551` 已指向 Android 0.6.5/code 43 与 iPhone 0.6.5/build 24。公网 APK 为 798242 字节，SHA-256 `5E862A0EB5AAF8B8E91425CB2B2F98BDBDB17399372F0DDDE44BBA9B9BA9DA58`；重新下载后哈希一致并已覆盖安装回 Redmi Note 8。IPA SHA-256 为 `A5CC0BD4D1229EC368D5D7B14C14364A4FDD4D38C5827E7B4ED6582A8E6E87BD`。

## 2026-07-31 三端剪切板、截图与局域网中转候选

- 版本统一为 Android 0.6.4/code 42、iPhone 0.5.3/build 22、Windows V4.1.2。中转只使用局域网、热点和设备直连，不接入坚果云或公网中继。
- Android 半屏悬浮窗、点外收起、1 分钟/30 分钟/永久暂停、截图三开关、待确认截图和主设备自动发送已落地；远端最新剪切写入系统剪切板。
- 三端 V2 任务增加可选中转元数据，按消息 ID 去重、跳数限制并在一小时后过期；Android/iPhone/Windows 均实现持久中转队列与下游接收后删除本节点副本。iPhone 只保证前台或系统允许的短暂后台时间，重新进入应用后继续。
- Android 更新副本改为纯英文 `.apk` 文件名，完成下载后依次检查 SHA-256、APK 可解析性、包名、版本号和签名；验证前不显示安装入口，失败副本删除并允许重新下载。
- 本机 Android 20 个测试套件共 64 项测试通过，Release APK 构建和 Release Lint 通过。候选包为 versionName 0.6.4/versionCode 42，797426 字节，v2 签名证书 SHA-256 仍为 `CAC54653FDFDBD19E0D9952FECA70E9B2A0530CE6676EB17A3EC24A56BE1848B`，不含 `REQUEST_INSTALL_PACKAGES` 和 Debug 标志。
- 当前 Windows 没有本地 C++ 工具链，iPhone 也不能在 Windows 编译；两端必须以本次源码提交的 GitHub macOS/Windows 构建结果作为候选包证据。
- 功能源码提交为 `201e449`。主账号 run `30613630387` 和第一备用 run `30613700017` 均在执行步骤前被账单/额度拦截；第二备用 run [`30613740118`](https://github.com/idmzwm-sys/team-video-workflow-build/actions/runs/30613740118) 的 Windows 编译与测试、Android 64 项单元测试/Release/Lint、iPhone 模拟器测试和真机 SDK 构建、远程服务闭环全部通过。
- 公开 Release 为 [`gallery-updates v0.6.4`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.4)，包含 APK、IPA、Windows EXE、显式源码 ZIP 和校验清单。更新索引提交 `9759a9c` 已指向 Android 0.6.4/code 42；从公网重新下载的 APK 为 797334 字节，SHA-256 `11F1BF88DE778734A7DB478466930D62F91BE0C62D0AC7F2DEA07E884F3043B9`，与索引一致。
- iPhone 数据线已重新识别，侧载后设备应用清单确认 `0.5.3`、build 22、最低 iOS 12.0 且描述文件已验证。该证据只证明签名覆盖安装成功；前台剪切板、中转、截图拒收和业务数据保留仍需在屏幕上逐项操作后记录。
- 当前没有可见的红米 ADB。红米 0.6.3 应用内升级、华为文件/回收站和多级热点拓扑均不得在设备接入前标记真机通过。

## 2026-07-30 Android 0.6.3 长剪切折叠与统一滚动

- 0.6.3/code 41 修复最新剪切完整高度把固定常用语挤出悬浮窗口的问题。超过 3 行或 140 字符的内容默认折叠并显示“展开”，展开后标题行按钮改为“收起”；收到不同的新内容时恢复折叠。
- 悬浮窗的最新剪切和固定常用语改为一个 ScrollView，标题栏、展开按钮和底部状态保持固定；普通 ClipboardActivity 也移除最新/常用语各自独立的嵌套滚动，使用一条纵向滚动链。
- 悬浮球静止长按 5 秒弹出关闭时长，支持 30 秒、5 分钟、1 天和永久关闭。临时期限持久保存并由服务定时恢复；永久关闭写回原设置开关，从设置重新开启时同时清除未到期的临时暂停。
- 本机 Gradle 9.4.1 下 62 项单元测试、Release 构建与 Release Lint 已通过；正式候选 APK 为 versionCode 41/versionName 0.6.3，大小 789834 字节，SHA-256 为 `7C7E2BED39A6AF99D73D53051162D75950D835FFEEDF98D6600B1F1B35605FF8`。APK 使用 v2 签名，证书 SHA-256 仍为 `CAC54653FDFDBD19E0D9952FECA70E9B2A0530CE6676EB17A3EC24A56BE1848B`，清单不含 `REQUEST_INSTALL_PACKAGES` 和 Debug 标志。
- 功能源码提交为 `e0b3c59`。公开 Release 为 [`gallery-updates v0.6.3`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.3)，更新索引提交 `cbdf4f7` 已指向 0.6.3/code 41；公网重新下载的 `album-Android-v0.6.3.apk` 为 789834 字节，SHA-256 与 raw 索引完全一致。Release 同时附带对应源码压缩包和 `SHA256SUMS.txt`。
- 主仓库 Actions run [`30528898747`](https://github.com/zwmopen/team-video-workflow/actions/runs/30528898747) 仍因主账号付款失败或 spending limit 在任务启动前被 GitHub 拒绝，四个 job 均未实际执行；它不是代码测试失败，本次 Android 交付证据采用上述同一源码的本机完整门禁。
- 当前无 ADB 设备连接；默认/最小窗口尺寸下的长文省略号、展开收起、继续下滑到末条话术，以及长按关闭、拖动不误触和到期自动恢复仍须在下一台实体手机补验。

## 2026-07-30 Android 0.6.2 深度可靠性修复

- 0.6.2/code 40 修复剪切板四条确定故障：同 IP 重连不补齐、点击固定话术不即时同步、多个存储实例并发覆盖、同一时间戳冲突在不同手机分叉。
- 所有剪切板文件操作在进程内使用共享锁；收到或创建新剪切后物理删除其他剪切记录，只保留当前最新一条。固定常用语和删除墓碑继续完整同步，不受剪切压缩影响。
- 悬浮面板改为可获取焦点，添加到窗口 180ms 后再读取剪切板，以适配 Android 10+ 只允许焦点应用读取的隐私边界；点新增先关闭面板，避免压住普通编辑页。点击任意最新值或固定话术都会立即保存并同步在线手机。
- 设备超过 15 秒在线窗口后再次出现，即使名称、IP 和作品数都没变，也会重新推送当前剪切快照。更新下载持续记录进行中/已校验完成状态，防止未安装前重复下载；安装完成后的首次启动移除本 App 跟踪的已下载 APK。
- 本机 Gradle 9.4.1 下 58 项单元测试、Release 构建与 Release Lint 已通过；正式候选 APK 为 versionCode 40/versionName 0.6.2，大小 786974 字节，SHA-256 为 `18470E8D6188F7BED083A769D3FC22F1BC2189759AA0108FD231E0404CD13B82`。APK 使用 v2 签名，证书 SHA-256 仍为 `CAC54653FDFDBD19E0D9952FECA70E9B2A0530CE6676EB17A3EC24A56BE1848B`，清单不含 `REQUEST_INSTALL_PACKAGES` 和 Debug 标志。
- 功能源码提交为 `4c9b132`。公开 Release 为 [`gallery-updates v0.6.2`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.2)，更新索引提交 `8565b63` 已指向 0.6.2/code 40；公网重新下载的 `album-Android-v0.6.2.apk` 为 786974 字节，SHA-256 与 raw 索引完全一致。Release 同时附带对应源码压缩包和 `SHA256SUMS.txt`。
- 主仓库 Actions run [`30527726117`](https://github.com/zwmopen/team-video-workflow/actions/runs/30527726117) 仍因主账号付款失败或 spending limit 在任务启动前被 GitHub 拒绝，四个 job 均未实际执行；它不是代码测试失败，本次 Android 交付证据采用上述同一源码的本机完整门禁。
- 当前无 ADB 设备连接；悬浮窗焦点读取、两机离线 20 秒后的自动补齐、固定话术即时同步和 DownloadManager 未安装重开均列为下一轮逐机实体测试，不能用本地自动检查代替。

## 2026-07-30 Android 0.6.1 可拖动缩放悬浮剪切板

- 0.6.1/code 39 移除 Android 首页左下角重复入口，只保留系统悬浮圆点；圆点改为全屏双轴拖动，首次默认位于屏幕高度约四分之一处。
- 圆点点击后直接显示带描边的系统悬浮剪切板，标题栏拖动窗口，右下角手柄调整宽高；圆点和窗口均持久保存位置，窗口同时保存尺寸。
- 悬浮面板改为“顶部最新剪切一条、下方固定常用语、右上角新增”；每次点开圆点会读取当前系统剪切内容。同步快照只包含最新一条剪切内容与全部固定常用语/删除墓碑，接收端按发送设备清除旧的同步剪切项，因此不会把整套历史灌给其他手机；在线设备即时收到，重新上线设备由发现流程补齐。
- 常用语初始化自电脑端权威 `01-正式SOP/01-前端私聊承接与拉群SOP.md` 的 8 条“推荐回复”。使用稳定 ID 且只补不存在的记录，因此用户编辑和删除产生的新版本/墓碑不会被下次启动恢复覆盖。
- 本机 Gradle 9.4.1 下单元测试、Debug/Release 构建与 Release Lint 已通过；正式 APK 为 versionCode 39/versionName 0.6.1，SHA-256 为 `96EA7FF25C164B6314CE3F59A62B68B98752257E2ADFDBD1AF664C465B64788F`，清单不含 `REQUEST_INSTALL_PACKAGES` 和 Debug 标志。
- 功能源码提交为 `827b04f`。公开 Release 为 [`gallery-updates v0.6.1`](https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.1)，更新索引提交 `3377d55` 已指向 0.6.1/code 39；公网重新下载的 `album-Android-v0.6.1.apk` 为 785066 字节，SHA-256 与索引完全一致。Release 同时附带对应源码压缩包和 `SHA256SUMS.txt`。
- 主仓库 Actions run [`30526135915`](https://github.com/zwmopen/team-video-workflow/actions/runs/30526135915) 因主账号付款失败或 spending limit 在任务启动前被 GitHub 拒绝，四个 job 均未实际执行；本次交付证据采用上述同一源码的本机完整 Android 门禁，不把账户账单故障写成代码测试失败。
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
## 2026-08-21 高级远程传送第一阶段：任务状态与幂等重试

- 当前正式基线仍为 Android 0.6.29 / iPhone 0.6.16；本阶段先增强 `remote-relay`，不把未完成的远程客户端混进手机正式包。
- `GET /v1/transfers/{id}`、`GET /v1/inbox`、`GET /v1/outbox` 现在返回 `uploaded`、`uploadedAt`、`uploadedObjectCount`、`uploadedCipherBytes`、`nextObjectIndex`，供客户端从已有任务恢复。
- 同一任务对象再次上传时，中继会核对 R2 现存对象大小和 `cipherSha256`，一致则返回 `reused=true`，避免断线重试产生重复写入。
- Android 与 iOS 接收服务启动时会在系统密钥存储生成远程签名/密钥协商用 P-256 密钥；私钥不进入日志、发现广播或网络请求。
- 验证：remote-relay 11 项协议测试通过、Node 语法检查通过、`git diff --check` 通过；本机 `tsc` 不在 PATH，类型检查仍以 CI 为准。
- 下一步唯一动作：实现三端设备凭证与远程会话客户端，先接入在线心跳/任务收件箱，再接密文上传下载；不得直接把 V2 局域网 HTTP 暴露到公网。
