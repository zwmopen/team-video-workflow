# Bug、根因与回归账本

只记录脱敏、可复现、可复用的结论。新增问题必须补齐现象、根因、修复、证据和回归要求，不能只贴原始日志。

> **账本积压说明（2026-09-20 记录）**：本文件最新条目此前停在 DSH-075（Android 0.8.12），
> 而实际版本已推进到 0.8.40，中间多轮修复未按本文件格式补记。DSH-076 起恢复记录，
> 中间缺口未回填（不冒充完整），建议后续按 `git log` 回溯补齐。当前最新条目为 **DSH-081**。

## DSH-079 iOS 信标缺版本扩展字段，电脑端把「泛流量篇数」当版本号显示（iOS 0.8.21 及以前；0.8.22 修复）

- **现象**（Windows 面板实机截图，非推理）：
  1. 苹果12 卡片永远显示 `相册 v0.8.3 (iOS 最新版 · build 11)`，而手机真机对账是 `v0.8.21 / build 92`；
  2. 同机 `笔记` 里 `手机储备 15 个（分类未上报）` —— 而安卓机同位置能显示「精准 x · 泛 y · 未分类 z · 总计 n」；
  3. 面板解析出的 `versionCode` 恰好等于该机 **泛流量作品数 11**（`device-presence.json` 实锤）。
- **环境**：iPhone 13,2 / iOS 26.6；`相册` 0.8.21（build 92，AltStore 侧载）；Windows「文件分发工作台（稳定版）」`src/server.js`
  `parseDevicePresenceBeacon`；协议见 `docs/PROTOCOL.md`「发现」。
- **根因（协议字段错位，两端各写各的）**：
  - 安卓 `OnlineService.sendBeacon()` 在第 9~11 位发 `base64url(appVersion)|versionCode|base64url(updateCapability)`，
    作品计数追加在 12~14 位；
  - iOS `IncomingTransferService.beaconData()` 当时**没有**发这三个字段，把作品计数直接接在第 9 位，
    于是电脑端按 `appVersion/versionCode/updateCapability` 解码：
    `parts[9]=base64url("0")` 解不出 → `appVersion` 空；`parts[10]=泛流量数` → 被当成 `versionCode`；
    `parts[11]=未分类数` → 被当成更新能力；真正的 `workCounts` 因 `parts.length < 15` 整块丢弃；
  - 前端 `app.js` 又对 iOS 写死 `` `相册 v${appVersion || "0.8.3"} (iOS 最新版 · build ${versionCode || 11})` ``，
    把空值兜成假值，于是「两个 bug 互相掩护」，肉眼完全看不出是协议错位。
- **修复（客户端，iOS 0.8.22 / build 93）**：
  1. `beaconData()` 补齐第 9~11 位（`IncomingTransferService.appVersion` / `appVersionCode` / `updateCapability`），
     作品计数自动落到电脑端期望的 12~14 位；
  2. `updateCapability` 用 `ipa-altstore-v1`（**不能**复用安卓的 `apk-push-v1` ——
     电脑端用该值判定「能否直接推送 APK 安装」，iOS 只能 AltStore 侧载，混用会导致误判）；
  3. 顺手把 `/v2/info` 也补上 `versionCode` / `updateCapability`（原先 iOS 的 `/v2/info` 缺 `versionCode`，
     服务端 `GET /api/online/phones` 只能显示 0）；
  4. 版本号单一真源仍是 `ios/project.yml`（`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`），
     Swift 侧统一经 `IncomingTransferService.appVersion/appVersionCode` 读取，不再各处复制 `Bundle.main` 取值。
- **证据**：`device-presence.json` iOS 条目 `appVersion:"" / versionCode:11 / workCounts:null`
  ↔ 该机泛流量作品数恰为 11；修复后同一字段应变成 `0.8.22 / 93` 且 `workCounts` 齐全。
- **回归要求**：任何「手机 → 电脑」的发现信标字段增删，必须**三端同时改并跑一次真机对账**
  （面板卡片显示 vs 手机 `设置 → 关于`），且**必须验证 `workCounts` 仍能被电脑端解析**——
  只测「版本号显示对了」会漏掉字段错位这类连带伤害。

## DSH-080 iOS 缺单播探测，电脑换网段后一直「拉取在线相册失败」（iOS 0.8.21 及以前；0.8.22 修复）

- **现象**：电脑 Wi-Fi 换网段（`192.168.0.107` → `192.168.1.27`）后，iPhone 切换「电脑在线」弹
  `拉取在线相册失败：<错误>`＋`正在自动搜索局域网内的电脑在线相册…`，长时间搜不到；
  同一时刻安卓机**手点一下在线相册就能读**。
- **环境**：`online_gallery_service.py`（端口 45835）服务侧正常（`code.staleCode=false`、`0.0.0.0:45835` 监听）；
  白名单 `~/.device-share-hub-authorized-devices.json` 的 `last_seen_ip` 停在旧网段
  ⇒ 换网段后**没有任何设备成功连上过**（`markBaseUrlGood` 才会登记）。
- **根因（两端发现通路不对等）**：
  - 安卓：**发** `ZWMDS2_GALLERY_DISCOVER` 单播/广播探测（`OnlineGalleryClient.java`），
    电脑端信标循环收到含 `ZWMDS2` 的报文会立刻**单播回**一条 JSON 信标（含当前 `url`），
    所以电脑一换 IP，安卓 2 秒内自愈；安卓还会监听周期性广播；
  - iOS：既收不到广播（`com.apple.developer.networking.multicast` 需 Apple 审批，AltStore 侧载拿不到），
    `OnlineGalleryClient.applyBeaconUrl()` **零调用者**、`beaconPort = 45832` 声明了却从未使用（死代码），
    唯一兜底是整段 /24 单播扫描（20 秒预算、48 并发）⇒ 电脑换 IP 后体验是「长时间连不上」；
    而 `resolveBaseUrl()` 优先复用旧的 `manual/custom/lastGood` 地址，用户看到的就是反复失败。
- **修复（客户端，iOS 0.8.22 / build 93）**：新增快轨 `LanDiscovery.probeBeacon()`：
  1. 用 BSD UDP socket 发广播探测（**发送不需要 multicast 权限**），目标为全局广播 + 各网段定向广播；
  2. 从**临时端口**收发，电脑端回信是**单播**到该端口 ⇒ 收单播同样不需要权限；
  3. 只认返回体里的服务标识 `DeviceShareHub-OnlineGallery`，避免误认其它 45832 占用者；
  4. 命中后 `setCustomBaseUrl(url)`；`ContentView.tryAutoDiscoverPc()` 改为**先快轨、失败再跑慢轨** /24 扫描；
  5. 网段枚举收敛为 `LanDiscovery.localBroadcastTargets()`，供文件传送信标与在线相册探测共用（原先两处各写一份）。
- **证据**：借 adb 安卓机当**外部探针**验证「手机→电脑」通路：
  `printf 'GET /api/online/status HTTP/1.0

' | toybox nc -w 6 192.168.1.27 45835` → `HTTP/1.0 200 OK`
  （安卓无 curl/wget，`toybox nc` 可用）；服务端 `GET /api/online/phones` 也能读到该 iPhone
  `192.168.1.154:45833 / appVersion 0.8.21` ⇒ 网络、防火墙、服务全通，问题只在 iOS 的选路。
- **回归要求**：
  1. 换网段（或改电脑 IP）后，手机端必须能在 **≤3 秒**内读到在线相册，不允许长期停在「拉取失败」；
  2. 「USB 数据线已连接」与在线相册**无关**（在线相册走 Wi-Fi HTTP 45835），
     排查时不要把它当作变量；
  3. 白名单**不是门禁**：读接口无鉴权，`device-register` 只是「下载即用」的辅助登记，
     「拉取失败」不要往 403/权限方向排查。

## DSH-081 列表明明已经显示 401 套作品，却仍弹阻塞式「拉取在线相册失败」——`hadData` 采样时机错位（iOS 0.8.22；0.8.23 修复）

- **现象**（iPhone 真机截图，非推理）：冷启动相册 App 后，界面**已经正常渲染出在线作品列表**
  （`全部 401 / 中秋 18 / 国庆 18 / 游戏 71`，卡片齐全、可读），却同时弹出一个**阻塞式**弹窗
  「操作没有完成 / 拉取在线相册失败：Could not connect to the server. / 正在自动搜索局域网内的电脑在线相册…」，
  必须点「知道了」才消失，且 10 秒后复截仍在。
- **环境**：iPhone 13,2 / iOS 26.6；`相册` 0.8.22（build 93，Sideloadly 侧载）；
  电脑 IP 已由 `192.168.0.107` 换到 `192.168.1.27`（与 DSH-080 同场景）；服务端 `/api/online/status` 健康。
- **根因（本地快照异步后到 vs. 失败判定提前采样）**：
  - `ContentView.loadOnlineData()` 在**发起请求前**算 `let hadData = !onlineWorks.isEmpty`，
    而本地快照是 `loadOnlineSnapshot()` **异步后到**的（读取磁盘 → 解析 JSON → 填 `onlineWorks` → `renderOnlineUI()`）；
  - 冷启动时序：请求发出（此刻 `onlineWorks` 还是空）→ 快照落地并渲染出 401 套 → 请求失败回包，
    此时 `hadData` 早已被固化为 `false` ⇒ 走「完全没有数据」分支，弹出阻塞弹窗；
  - 换句话说：**弹窗的触发条件与屏幕上真实有没有内容脱钩**。判定用的是「请求发出那一刻」的快照，
    却用来描述「回包那一刻」的界面状态 —— 判据与被判对象不同步。
  - 附带问题：即使确实没有任何数据（首次安装 / 快照损坏），也是**先弹阻塞弹窗再自愈**，
    于是电脑换网段这一个「本该 1 秒自愈」的常见场景，用户第一眼看到的永远是「拉取失败」。
- **修复（客户端，iOS 0.8.23 / build 94）**：
  1. 删除请求前的 `hadData` 采样，改为在 `group.notify`（**回包时刻**）直接用 `!self.onlineWorks.isEmpty`
     判定 —— 快照只要已经落地就会被正确算作「已有数据」，只发轻量 toast、保留列表、不弹窗；
  2. 无数据分支改为**先非阻塞、后升级**：先 `showToast("⚠️ 正在搜索电脑在线相册…")`，
     再 `tryAutoDiscoverPc(alertOnFailure:)`；**只有自愈也失败**（快轨 2.5s 无应答 + 慢轨 /24 扫描也没找到），
     才把原来的阻塞弹窗放出来；
  3. `tryAutoDiscoverPc` / `finishAutoDiscover` 增加 `alertOnFailure: String? = nil` 参数：
     自愈因 15 秒冷却或在途（`autoDiscovering`）**压根没跑起来**时，也如实弹窗，
     避免把错误静默吞掉（这是「守卫必须真的会响」的同一条原则）。
- **证据**：
  - 修复前真机截图 `_iphone_now.png` / `_iphone_after10s.png`：列表 401 套正常显示 + 阻塞弹窗并存；
  - 客端自愈其实**已经成功**：电脑端 `GET /api/devices/live-state` 显示 iPhone
    `appVersion 0.8.22 / versionCode 93 / workCounts {traffic:11, uncategorized:4, total:15}`，
    说明弹窗只是残留提示，数据通路是通的 —— 因此**不能**把该弹窗当成「连不上」的证据；
  - 版本字段对齐（DSH-079）也在同一时刻被证实：`versionCode` 已从错位的 `11` 变成真实的 `93`，
    而 `traffic` 仍是 `11`，两个数各归其位。
- **回归要求**：
  1. **「有没有数据」这类判定必须在消费它的同一时刻采样**：不要把「请求发出前」的状态缓存下来，
     用于「请求返回后」的分支判断 —— 凡是「异步填充 + 同步读取」共存的字段都要按这条查一遍；
  2. 冷启动 + 电脑换网段的组合场景，用户**不应看到任何阻塞弹窗**；允许出现一条自动消失的 toast；
     阻塞弹窗只保留「所有发现手段都失败」这一种情形；
  3. 验证必须看**真机屏幕**（`pymobiledevice3 developer dvt screenshot` 可免越狱截屏），
     不能只看日志或接口 —— 本 bug 的全部症状只存在于 UI 层。

### DSH-081 · 0.8.24 补漏：修对判定时机之后，「有缓存数据」这条支路把自愈一起关掉了

- **现象**（真机实测，非推理）：0.8.23 冷启动时**不再弹窗**（0.8.23 的修复生效），
  但屏幕停在**旧列表**，30 秒后 App 缓存的电脑地址**纹丝不动**：
  `customPcServerUrl` 仍是旧网段 `http://192.168.0.107:45835`。
  ⇒ 「看得见的旧列表 + 静默卡死」，**比原来的阻塞弹窗更糟**：
  用户以为一切正常，其实列表是陈的，点「发布 / 删除」都会失败。
- **复现方法（可复用，无需改服务端、不影响在线的其它手机）**：
  用 AFC 把 App 偏好里的 `customPcServerUrl` 改成旧网段地址再冷启动即可 ——
  `apps pull <bid> Library/Preferences/<bid>.plist` → 改字段 → `apps push` 回设备 →
  `developer dvt launch --kill-existing <bid> --userspace`；
  判据看两处：**屏幕**（有无弹窗）与**回读的 plist**（地址有没有被自愈改写）。
- **根因（两个判断被耦合）**：失败分支里 `tryAutoDiscoverPc()` **只写在「完全没有数据」那一支**；
  「有快照」那一支只发 toast。而 0.8.22 能自愈，恰恰是靠 `hadData` 被异步快照误判成 `false`、
  走错了分支才顺手跑了一次自愈。0.8.23 把误判修对，**同时把自愈也关掉了**。
  ⇒ 铁律：**有没有缓存数据只决定「用 toast 还是用弹窗」，绝不能决定「要不要去找新地址」。**
- **修复（iOS 0.8.24 / build 95）**：失败即在「有数据」「无数据」两支都调 `tryAutoDiscoverPc()`
  （仍受 15 秒冷却保护，不会刷屏）；`alertOnFailure:` 只在无数据支路传，保证弹窗语义不变。
- **证据**：0.8.23 下表（实测）——冷启动后无弹窗、`customPcServerUrl` 仍为旧网段、30s 未改写；
  0.8.24 应能在数秒内把它改写成 `http://192.168.1.27:45835` 并把列表刷成最新。
- **回归要求**：
  1. 「缓存地址失效」这一场景必须**同时断言两件事**：屏幕不弹阻塞弹窗 **且** 缓存地址被自愈改写。
     只断言前者，就会漏掉本条目这种「静默卡死」；
  2. 凡是「有数据走 A 分支、无数据走 B 分支」的降级逻辑，都要逐支检查
     **「自愈 / 重试 / 兜底」这类补救动作是不是被漏写在某一支里**；
  3. 修一个误判时，必须回头看**该误判此前顺带触发过哪些正确行为** —— 修好了误判却丢掉了副作用，
     净效果可能是退步。

## DSH-077 空壳守卫被自己的靶子骗过：整段口径放行「标记齐全但正文是模板骨架」的作品（服务端，无客户端改动）

- **现象**：
  1. 在线作品全量 384 套里，**9 套**（全部位于 `已发送0次（抖音小红书可发）`）在手机端可点出
     「规避营销版 / 种草版 / 大纲方案版」等按钮，但点下去拿到的**不是文案，而是产线的填写说明**：
     `抖音短平快口播脚本，痛点切入+亮点+留资号召` / `[小红书种草正文，带两日详细行程排期、亮点提炼与真实避坑，拒绝空话]` /
     `HR方案决策版大纲，包含方案名称、适用对象、预算参考、决策亮点与服务保障`。
     **骨架可以一路走到「发布」这一步，是本条目最严重的地方。**
  2. 这些作品在 `/api/online/works` 里 `copyMissing=false`、`hasCopyText=true`，**看起来完全健康**。
- **环境**：`online_gallery_service.py`（端口 45835）；判定阈值 `MIN_COPY_DISTRIBUTABLE = 30`；
  手机端 `PlatformCopyParser.parseAvailablePlatforms` + `MainActivity.isCopySubstanceMissing`。
- **根因（与 DSH-076 的 `staleCode` 同源，是第二例「假闸门」）**：
  - 守卫的判定对象是**整段 `copyText`**：`copy_substance_len(copy_text) >= 30`。
    这批骨架整段实质 **107 字** ≥ 30 → 直接放行。
  - 但手机端是**按单个平台槽位**独立生成按钮的（`<<<MK_START>>> … <<<MK_END>>>` 逐块解析），
    用户实际消费的粒度是**单个槽位**，而不是整段。
  - **闸门测的粒度与用户消费的粒度不在同一层级** → 闸门形同虚设。
    而该守卫的注释明写它存在的目的正是「杜绝**标记齐全但正文全空**」——
    它被自己针对的失效模式精确命中。
  - 手机端的 `copyMissing` 是**本地算的**（`isCopySubstanceMissing(work.copyText)`），不读服务端字段，
    且粒度同样是「整段」，所以两侧一起看不见这个问题。
- **修复（`scripts/online_gallery_service.py`，纯服务端）**：
  1. 新增 `MIN_PLATFORM_SUBSTANCE = 30`、`_PLATFORM_BLOCK_RE`、`_PLACEHOLDER_SLOT_RE`、
     `audit_platform_slots()`、`sanitize_platform_copy()`：**下发前按槽位**剔除
     「命中占位语特征」或「实质字数 < 30」的块；
  2. **用按 span 删除、不用按块重建** —— 这样 `<<<COPY_FORMAT:n>>>` 头部与
     `<<<VERSION_START:名>>>` 这类本函数不解析的块能原样保留，不会误伤；
  3. `copyMissing` 口径升级为「净化后无真实槽位 ∨ 整段实质 < 30」；
  4. **只净化下发载荷，绝不修改磁盘原始文件**；`searchBlob` 仍用未净化原文，
     保证被剔除的作品**照样能被关键词搜到**；
  5. `/api/online/status` 新增 `slotGuard` 块，计数**按每轮扫描重置**，`slotsDropped > 0` 视为响铃。
  - **手机端零改动**：净化后整段实质自然 < 30，手机端既有 `isCopySubstanceMissing` 会整块置灰标红。
- **证据（重启服务后线上全量逐条对照，非推理）**：
  - `/api/online/status` → `slotGuard: {scanWorksAffected: 25, scanSlotsDropped: 58}`
    （58 里多数是既有的 **0 字空槽位**，手机端本来就不出按钮；9 套骨架才是本次的行为变更）；
  - `code.staleCode = false`，`scriptShaRunning == scriptShaOnDisk == b23f0283a7fb`（确认跑的是新代码）；
  - 逐条对照脚本 `_verify_slot_guard_effect.py`：**9 套从「2 个占位按钮」→ 整块置灰**，
    与 `/status` 的 `worksAffected` 完全对齐；**真实作品误伤 0 套**；
    1 套混合作品（真 XHS + 占位 XHS_2）只剔占位槽位、保留真实槽位，未整块置灰；
  - 阈值安全性核查：全量 554 个槽位里，**没有任何一个真实槽位落在 1–29 字区间**
    （最短的真实槽位都 ≥ 30 字；< 30 的只有 0 字空槽与占位骨架），故「< 30 即剔除」不会误伤。
- **回归要求**：
  1. `test_online_gallery_service.TestPlatformSlotGuard`（6 项）必须全绿，
     其中「守卫必须接线进载荷」一项用于防「只声明不调用」型假闸门；
  2. **新增守卫必须同时提交「还原成错误实现会 FAIL」的证明**：
     把 `sanitize_platform_copy` 还原为原样返回后，核心 3 条断言必须 FAIL
     （已实测 3/3 FAIL）。只有绿色、没有失败证明 = 不能证明测试在守东西；
  3. **新增判定必须用真实磁盘文件跑端到端**（`WorkScanner._inspect_work_dir`），
     只测 helper 会重蹈 `staleCode` 的覆辙；
  4. 上游产线（网页CDP 出图链路）需补一道「骨架不得落入可分发目录」的检查 ——
     本修复是**下游兜底**，不是根因消除。

## DSH-078 在线相册测试套件长期红灯：用例断言已被替换的旧规则 + 用例间互相污染（服务端测试）

- **现象**：`python -m unittest test_online_gallery_service` 长期 **10 项 2 败**，
  长期无人处理；红灯成为常态，等于没有测试。
- **根因（两个，且都有同一味道：测试与真实行为脱节）**：
  1. `test_two_uses_protection_rule` 断言「首次使用**不移动**、`remainingUses == 1`」，
     而**现行铁律**（`use-work` 处理器注释标注为「核心业务铁律」）是
     「只要手机端使用过一次，电脑后台就**物理移入** `_已发送1次（微信公众号可发）`」，
     故正确返回是 `moved=True`、`remainingUses=0`。**实现没坏，是用例还停在旧规则。**
  2. 该用例把**共享扫描根**里的样例作品真的挪走了（`setUpClass` 建的 `work1_dir`），
     于是按字母序后跑的 `test_works_search_and_filter` 搜不到作品 → 误报 0 条。
     **用例互相污染 → 结果依赖执行顺序。**
- **修复（`scripts/test_online_gallery_service.py`）**：
  1. 该用例自带独立作品目录（唯一 ID），改用 `addCleanup` 复原：
     删除自建目录 + 清扫可能落地的 `_已发送1次` / `_垃圾作品` / `_不合格成品合集` 中的副本，并 `scan(force=True)`；
  2. 断言改为现行规则：首次使用 `moved=True`、`remainingUses=0`、stage0 里目录已不存在、
     标签文件随作品一起搬走且 `useCount=1`；
  3. 第二次使用：`useCount=2`；`_move_work_to_stage1` 对已在目标位置的目录是**幂等**的
     （返回「作品已位于…」，`moved=True` 表示「已就位」而非「又挪一次」），
     故断言改为**真正的业务不变量：不得产生重复目录**（`_已发送1次` 下该作品目录数 == 1）。
- **证据**：`HEAD` 基线复跑 10 项 2 败（用 `git show HEAD:` 取旧文件到临时目录，非破坏性）；
  修复后 **16 项 0 败**。
- **回归要求**：
  1. 测试套件必须**全绿**；出现红灯即刻处理，不允许「红着红着就习惯了」；
  2. **业务规则变更时必须同步改断言**，并在用例 docstring 里写清「旧规则是什么、为什么变」——
     否则下一个人只会看到一条莫名其妙一直红的用例；
  3. **任何会改动共享 fixture 的用例必须自带清理**（`addCleanup`），
     套件必须做到**单跑与全跑结果一致**。

## DSH-076 在线回收站长期卡「正在读取」＋ 缩略图链路静默降级（Android 0.8.40 / versionCode 151；iOS 0.8.17 / build 88）

- **现象**：
  1. 手机端进入「在线相册 → 回收站」，界面长时间停在「正在读取电脑在线回收站…」，卡片迟迟不渲染；
  2. `logcat` 中 `OnlineGalleryClient: loadThumbnail failed ... timeout` 持续刷屏；
  3. 电脑端 `/api/online/image` 的 `thumb=0` 与 `thumb=1` **没有区别**，返回的都是原图。
- **环境**：Windows 中控（Python 3.11.4 + `pythonw` 常驻，端口 45835）＋ Android 0.8.39 真机（Redmi 13C，同一 Wi-Fi）；作品库 383 套，单张作品图最大 3–4 MB PNG。
- **根因**（一条主因 + 两个独立缺陷）：
  1. **主因｜服务进程跑着旧代码**：`online_gallery_service.py` 在 14:44 被改过并加入了缩略图链路，但常驻进程启动于 06:58 —— 进程里根本没有那段代码，`thumb=1` 参数被**静默忽略**并回落成原图，全程零报错、零日志。手机端于是为几百张卡片拉 GB 级原图，4 线程 + 8s 超时 ⇒ 大面积超时 + 原图解码 GC 风暴 ⇒ UI 冻结。
     铁证：`%TEMP%\gallery_thumb_cache` 目录**不存在**（而该目录在 import 时就会被创建）⇒ 进程里没有缩略图代码；进程 PID 启动时刻 06:58:54 vs 脚本 mtime 14:44。
  2. **独立缺陷 A｜iOS 图片契约缺失**：`/api/online/image` 只认 `id`+`file` 参数，而 iOS 端一直用 `?path=` ⇒ iOS 在线图片**一直 HTTP 400**。
  3. **独立缺陷 B｜iOS 版本号双源**：`ios/project.yml` 里版本号有**两处**（`settings.base` 与 `info.properties` 硬编码），升版本只改前者时 xcodegen 用后者覆盖生成的 Info.plist ⇒ IPA 版本与 CI 断言不一致 ⇒ iOS 产物**发不出去**，而 `android-build` 却是绿的，极易误判为「iOS 环境抖动」。
- **修复**：
  1. **缩略图链路整形（服务端）**：复活「只声明从未使用」的内存缓存（`THUMB_CACHE` 死代码）为带 LRU 淘汰的真实缓存；新增同图并发生成单飞锁；三级取图「内存 → 磁盘 → PIL」；Pillow 缺失/生成失败时打印明确日志并在响应头下发 `X-Thumb: fallback`（**杜绝静默降级**）；`/api/online/status` 新增 `thumbnail` 健康块。
  2. **补齐 iOS 旧契约**：`/api/online/image` 支持 `path` 的三种形态（绝对路径 / 相对成品库路径 / 裸文件名经索引解析），并做目录穿越防护（越界一律 404）。
  3. **客户端调度整形（Android）**：缩略图改用独立 8 线程池；新增磁盘缓存与在途请求去重；按 384px 目标边长降采样解码；新增失败重试一次（900ms）；
     在线列表缩略图由「每卡片前 4 张立即发请求」（382 作品 ⇒ 1500+ 请求齐压线程池）改为**整页调度**（首发预算 18 张 + 260ms/6 张渐进放行）。
  4. **代码新鲜度自检（防复发闸门）**：`/api/online/status` 新增 `code` 块，用「启动时脚本内容 SHA」对比「实时读盘脚本内容 SHA」，不一致即 `staleCode=true` 并提示重启服务；开机自启脚本由「直连 pythonw」改为「调用启动器 `-Restart`」，杜绝新旧进程并存。
  5. **iOS 版本号回到单一源**：`info.properties` 改为引用 `"$(MARKETING_VERSION)"` / `"$(CURRENT_PROJECT_VERSION)"`。
  6. **缩略图磁盘缓存加上限**：磁盘缓存以「路径+mtime」为 key、旧条目永不失效，此前无任何上限；现加 `MAX_DISK_ENTRIES = 2000`，超限按 mtime 淘汰最旧的一批、只留 90%，且低频触发（每 200 次写盘才真扫一次目录）。
- **证据**（修前 → 修后）：
  1. 单张「缩略图」体积 2,678.7 KB → **37.1 KB**；400 张总耗时（并发 8）10.79 s → **0.53 s**；P95 0.164 s → **0.021 s**；吞吐 37 → **754 张/s**。
  2. 真机（Redmi 13C / 0.8.39）一次进页取图 **1500+ → 231 次**，`loadThumbnail failed` 刷屏 → **0**，「正在读取」约 5.8 s 消失。
  3. CI run `35515813427`（源码提交 `95e5d47`）：`android-build` ✅ / `ios-altstore-build` ✅ / `windows-portable` ✅ / `publish-gallery-updates` ✅；`album-iOS-v0.8.17-altstore.ipa`（21.3 MB）已随 `v0.8.39` release 正常发布；线上 `latest.json` 已刷新。
  4. 仅 `remote-relay-check` ❌（长期已知红点，与本次改动无关）。
  5. 服务端自带单测 9 项 2 败（`test_two_uses_protection_rule` / `test_works_search_and_filter`），已用 `git archive HEAD` 取原版测试确认**同为失败**，属历史遗留。
- **回归要求**：
  1. 手机端进入在线回收站必须在数秒内渲染出卡片，「正在读取」不得长时间停留；
  2. 一次进页取图次数应稳定在数百次量级，不得回到 1500+；
  3. `/api/online/status` 的 `code.staleCode` 必须为 `false`（为 true 即说明服务在跑旧代码，需重启）；
  4. 改完服务端脚本后**必须重启服务**，重启后 `staleCode` 应回到 `false`；
  5. `git checkout` / `git pull` 造成的纯行尾符变化**不得**触发 `staleCode`（已单测锁定）；
  6. 发布前核对 EXE/APK/IPA、源码提交、Release、Android 更新索引与 SHA-256 一致。

## DSH-075 跨平台已用作品临时浮动置顶与【重置】误触回滚（Android 0.8.12 / iOS 0.8.5）

- **现象**：
  1. 用户在手机端（Android/iOS）从 20+ 套作品中挑选某篇发送/使用后，过会儿想回看复盘分析该篇作品时，该卡片因变灰且沉底（自然排序），需要滑动到很长列表的深处才能重新找到，操作极不方便；
  2. 用户在误触点击了发布、或者临时改变主意想明天再发时，该作品已经记录为已发送（shareCount > 0）并排期了 1 小时自动移入回收站，缺乏撤销与回滚机制。
- **根因**：
  1. 列表排序只依赖自然名称升序（Natural Sort），未赋予已使用作品（shareCount > 0）以置顶权重，导致刚操作过的焦点作品与未发作品混杂或滑离视口；
  2. 既有状态流只有“准备分享 -> 计数自增 -> 安排回收”的正向单向流动，缺少重置分享状态（Reset Share）的逆向回滚事务，导致用户无法取消排期或恢复初始绿色未用状态。
- **修复**：
  1. **双端已发作品临时浮动置顶（Floating Top Retention）**：
     - Android（`WorkLibrary.java`）与 iOS（`WorkScanner.swift`）：当 `shareCount > 0` 时优先浮动置顶至列表顶部，卡片标题附加 `📌 ` 标识；多个已用作品按最近使用时间倒序排列（新用在最顶），未用作品维持自然名称升序；
  2. **卡片新增【重置】状态按钮（Reset Share Action）**：
     - Android（`MainActivity.java`）与 iOS（`ContentView.swift`）：在已分享卡片的【删除】按钮左侧动态显示深灰拟态【重置】按钮；
     - 单击弹窗确认后，调用 `resetShare`：清空 `shareCount`、各平台计数、`used = false`、清除 `deleteScheduledAtMs` 自动回收排期，并移回普通列表排序；
  3. **版本升级**：Android 升级至 `0.8.12 (build 123)`，iOS 升级至 `0.8.5 (build 76)`。
- **回归要求**：
  1. 分享后作品立即加上 `📌 ` 并浮动置顶到第一位，多篇置顶按使用时间倒序；
  2. 点击【重置】确认后，作品瞬间回绿、计数归零、移出置顶归位回下方常规列表；
  3. 取消自动删除排期后，后台定时清理不会将其误删移至回收站。

## DSH-074 Android 删除卡片闪现回魂修复与 20+ 作品滑动卡顿根除（Android 0.8.11 / versionCode 122）

- **现象**：
  1. 用户在手机端点击删除作品移到回收站后，卡片虽然瞬间消失，但很快又在列表中“闪现回魂”，片刻之后才再次消失；
  2. 手机端作品数量超过 20 套时，主界面滑动出现掉帧、卡顿。
- **根因**：
  1. **异步搬移空窗期扫描回魂**：`optimisticRemoveWorks` 虽在主线程瞬间移除卡片，但后台单线程 `worker` 执行物理挪迁及 SAF 移动需数百毫秒；在此期间若触发了生命周期刷新、广播刷新或分类筛选，`library.listActive()` 仍会读出未完成搬移的物理文件，并将旧作品重新回填渲染（Flash back），待物理搬迁完毕的下一次刷新才真正抹除；
  2. **并发解码抢占 UI 线程**：`previewStrip` 对每个卡片无节制抢先解码 8 张缩略图（25 套作品即并发排队 200 个解码任务），且 `THUMBNAIL_EXECUTOR` 为普通线程优先级，密集的解码完成事件狂轰主线程 Looper，与用户的滚动和触摸事件抢占 CPU，引发 GC 抖动与卡顿。
- **修复**：
  1. **内存黑名单强制隔离（Pending Trash Barrier）**：
     - 在 `MainActivity` 引入线程安全的 `pendingTrashIds` 集合；
     - 删除触发瞬时立即登记目标 ID，在 `renderWorks`、`refreshWorks`、`applyCategoryFilter` 等全链路渲染与数据入口处进行黑名单强制拦截过滤，无论何时发生后台扫描或数据刷新，已删卡片绝对不再重返列表；后台物理移动彻底完成后自动释放；
  2. **缩略图低优先级调度与视口感知裁剪**：
     - `THUMBNAIL_EXECUTOR` 设置 `Thread.MIN_PRIORITY`，子线程执行时主动声明 `Process.THREAD_PRIORITY_BACKGROUND`，内核级让出 CPU 给主线程；
     - 单卡片瞬时并发预加载缩减至 4 张（横向视口满屏容纳极限），超出部分按需滚动加载；并在解码前核验视图 Tag 标签，已被划走的复用视图立即跳过解码；
  3. **版本号升级**：升级至 Android `v0.8.11`（`versionCode: 122`）。
- **回归要求**：
  1. 删除作品移到回收站后，卡片在任何生命周期或刷新触发下均绝对不许闪现回魂；
  2. 20+ 套作品长列表上下滑动丝滑流畅无卡顿；
  3. 编译打包与发布验证通过。

## DSH-073 Android 相册 App 回收站秒删性能与乐观 UI 彻底重构（Android 0.8.10 / versionCode 121）

- **现象**：
  在手机端相册列表点击作品卡片“删除”按钮并确认移到回收站时，界面无任何即时视觉反馈，卡顿冻结近 5 秒后才刷新并弹出提示，操作响应极其迟钝，体验严重不佳。
- **根因**：
  1. **UI 线程零即时响应**：原 `moveSelectedToTrash` 逻辑将所有任务提交给单线程 `worker` 执行后，主线程没有任何先验（Optimistic）视图变更，用户误以为点击无效；
  2. **物理搬迁串行阻塞**：`moveToTrash` 和 `ExternalTrashManager.moveTrashedSource` 涉及多张高清大图跨目录移动与 SAF（Storage Access Framework）IPC 跨进程调用，物理 I/O 耗时达数百毫秒至数秒；
  3. **最严重瓶颈——全量维护扫描与重绘**：后台搬迁完毕后，在主线程同步调用了 `refreshWorks()`，而 `refreshWorks()` 内部无差别同步调用了 `CleanupCoordinator.run(this)`（全盘遍历扫描过期缓存、读取全量 active/trash properties 元数据、执行全量 maintain 扫描，并对所有作品重新执行 SAF 目录存在性检测），并粗暴清空 `worksContainer.removeAllViews()` 将页面上全部卡片 View 彻底销毁重建，触发所有缩略图重新异步解码与布局重排！
  4. 回收站单项恢复（`restore`）与清空（`clearTrash`）同样存在调用 `refreshWorks()` 的全盘阻塞机制。
- **修复**：
  1. **瞬时乐观 UI（Optimistic UI）**：
     - 为每个卡片 View 绑定对应作品 ID（`card.setTag(work.id)`）；
     - 新增 `optimisticRemoveWorks(Set<String> ids, String toastMessage)`：
       - 主线程下一帧（< 16ms）瞬间从内存活跃集 `renderedWorks` 中剔除目标 ID；
       - 通过容器关联查找卡片，调用 `worksContainer.removeViewAt(i)` 触发 Android 原生 `LayoutTransition` 180ms 丝滑折叠与渐隐过渡；
       - 列表为空时瞬间切换至空态占位提示；
       - 瞬间更新顶部分类 Tab 徽标数字（`updateCategoryCounts`）与标题栏计数；
       - 弹出即时轻量 Toast（如“已移到回收站”）；
  2. **物理文件与 SAF 搬迁完全后台异步解耦**：
     - 后台 `worker` 静默完成文件挪移与属性写入；
     - 静默调用 `OnlineService.publishWorkInventory(this, active)` 更新在线心跳与广播，**彻底剔除成功路径上的 `refreshWorks()` 与 `CleanupCoordinator.run(this)`**；
     - 仅在物理移动发生严重异常时，主线程弹出错误 Toast 并由下一次页面自然生命周期进行对账；
  3. **回收站与恢复操作全链路对齐秒级响应**：
     - `restore(String id)`：回收站界面瞬间乐观移除恢复项并刷新徽标，后台异步还原属性并静默发布心跳；
     - `clearTrash()`：确认后瞬间清空容器并切换为空态提示，后台静默批量删除物理文件与 SAF 源；
  4. **版本递增**：升级至 Android `v0.8.10`（`versionCode: 121`）。
- **证据**：
  1. 单元测试 `build-local.ps1 -AndroidOnly`（单测全绿，assembleRelease 编译打包生成 `app-release.apk`）；
  2. 通过 ADB 覆盖安装至华为 P30（`8KE0219924003568`），通过前台 live 截图确认 `v0.8.10 (121)` 正常启动运行；
  3. 现场实操验证：原移入回收站的 2 项作品在回收站中瞬间恢复成功，主相册作品数即刻恢复为 8 篇，回收站归零，分类角标即时递减/递增，卡片移除折叠流畅无感，耗时感官从 5000ms 降至 0ms。
- **回归要求**：
  1. 点击“删除”移到回收站、“恢复作品”以及“清空回收站”，必须在点击确认瞬间（<16ms）卡片消失，绝不许出现等待转圈或冻结；
  2. 乐观移除后分类角标数量和标题作品数必须即刻减 1，后台物理搬迁成功后在线 Beacon 作品计数保持准确；
  3. 单元测试与构建流水线保持 100% 绿灯。

## DSH-072 缩略图手势精准分流、卡片边框裁剪溢出根除与宽屏预加载优化（Android 0.8.8 / versionCode 119）

- **现象**：
  1. 用户在卡片小缩略图上左右滑动浏览图片时，容易误触发外层全屏左右切 Tab 手势；
  2. 横向滑动缩略图预览条时，图片滑动边缘突破白色圆角卡片边框，产生视觉溢出穿透；
  3. Redmi K60 等 1080p 宽屏设备上首屏卡片第 5、6 张图片出现未加载灰白方块。
- **根因**：
  1. `SpringScrollView.onInterceptTouchEvent` 对横向滑动统一拦截，未在 ACTION_DOWN 时根据触摸落点坐标检测子级 `HorizontalScrollView` 并做事件分流；
  2. `MainActivity.card()` 与 `previewStrip()` 未开启双向视图裁剪（`setClipChildren(false)`），导致子视图在平移滚动时穿透卡片圆角描边外框；
  3. 异步缩略图预加载阈值固定为 `index < 4`，少于宽屏设备一行能容纳的 5~6 张预览图。
- **修复**：
  1. `SpringScrollView` 增加 `findHorizontalScrollViewAt(View, int, int)` 递归检测，当触摸在缩略图预览条内时标记 `touchStartedInHorizontalChild = true`，父级绝对不拦截横向手势；触摸在标题、按钮或卡片留白时才触发顶部分类 Tab 秒级切换；
  2. `MainActivity.card()` 与 `previewStrip()` 严格开启 `setClipChildren(true)` 与 `setClipToPadding(true)`，双向裁剪锁死在卡片 14dp 内边距中，彻底阻绝视觉溢出；
  3. 预加载阈值由 `index < 4` 提升至 `index < 8`，首屏第 5、6 张小图瞬间显示，消除灰方块；
  4. 同步更新 `verify-work-card-ui.mjs` 与 `verify-p2p-invariants.mjs`，确保全套端到端校验脚本 100% 绿灯。
- **证据**：
  1. `build-local.ps1 -AndroidOnly`（单测 22 项、assembleRelease、lintRelease）通过，版本号 `0.8.8` / `119`；
  2. 5 项核心验证脚本（p2p-invariants, removed-surfaces, task-receipts, work-card-ui, auto-mobile-update）全部绿灯；
  3. Redmi K60 与 Redmi 13C 实机安装覆盖，截图实测滑动不切 Tab、图片绝不破框、灰白方块彻底消失。
- **回归要求**：
  1. 缩略图横滑必须保持原位平滑滚动，绝不能误切 Tab；卡片空白区域左右滑必须切换 Tab。
  2. 缩略图左右滑动边界必须严密限制在白色卡片边框内。
  3. 5 项 Node 检验脚本与 Android 单元测试必须保持 100% 通过。

## DSH-071 手机端虚假“接收失败”通知刷屏、传送界面设备发现主动广播与全屏分类左右滑手势升级（Android 0.8.7 / versionCode 118）

- **现象 1**：手机相册未发起传送时，系统通知栏频繁弹出“接收失败 Broken pipe”弹窗，传送界面历史记录充斥大量“接收失败 Broken pipe”垃圾日志。
- **根因 1**：PC 端 Electron `server.js` 探活定时器（`fastDeviceProbingTimer`）每 5 秒发起一次 500ms 超时的 TCP/HTTP 探针；探针超时关闭 socket 时，手机端 `HttpRequest.readLine` 在 0 字节 EOF 时抛出 `HttpError(400, "连接提前结束")`，`handleHttp` 将其捕获并无差别作为传输失败记录到 `OperationLog` 并弹出通知。
- **修复 1**：
  1. `HttpRequest.readLine` 识别 0 字节 EOF 作为客户端正常断开连接（返回 `null`），不抛出 HttpError。
  2. `handleHttp` 捕获异常时严格校验 `isIncomingTransferPath`，只有真实文件/任务传输请求失败才弹出通知并写入操作记录，完全屏蔽后台探测与无害连接断开。
  3. `OperationLog` 增加 `clear(context)`，并在 `TransferActivity` 增加“清空”按钮，一键抹除历史虚假记录。
- **现象 2**：传送界面无法发现同局域网设备（因部分 Wi-Fi 路由开启 AP 隔离阻断点对点广播），且界面缺少主动刷新触发按钮。
- **修复 2**：传送界面增加“🔄 刷新设备”按钮，主动在 UDP 45832 广播 `ZWMDS2_DISCOVER`，促使局域网内对端设备立即回送自身 Beacon。
- **现象 3**：用户左右滑动卡片时希望能切换顶部分类 Tab，同时手指在卡片底部的横向缩略图预览条上滑动时应只滚动缩略图，互不干扰。
- **修复 3**：
  1. `SpringScrollView` 增加横向滑动手势监听器 `SwipeListener`（`onSwipeLeft` / `onSwipeRight`），利用 `VelocityTracker` 与 `minSwipeDistance = dp(42)` 精确识别左右滑手势。
  2. 尊重子 `HorizontalScrollView`（预览图条）的 `requestDisallowInterceptTouchEvent`，滑动缩略图时只滚动图片条；滑动卡片其余区域（标题、按钮、留白）时触发分类 Tab 快速切换（内存级秒切，分类按钮自动居中平滑滚动）。
- **证据**：
  1. 单元测试 `gradle.bat :app:testDebugUnitTest` 22 项全过；
  2. 编译 Release APK 安装至红米 13C (`69PNFQUCT4XGKZRO`) 与红米 K60 (`e3b58850`)；
  3. 真机 live 验证：卡片区域左右滑动即刻切换分类（全部 -> 团建游戏 -> 象山）、分类 Tab 自动居中平滑对齐；缩略图区域左右滑动正常滚动图片；传送页一键清空日志、“🔄 刷新设备”广播发送正常、虚假接收失败通知彻底消除。
- **回归要求**：
  1. 缩略图横向滑动不能触发分类切换，卡片其他区域左右滑动必须秒级切换分类。
  2. 局域网探测断开不产生任何系统通知与操作记录。
  3. 传送界面“清空”按钮能即刻持久化清空历史记录，“🔄 刷新设备”按钮能主动触发组播与广播。

## DSH-070 移动端把会话追踪 TXT 当成可复制文案（Android/iPhone 0.8.3，修复候选）

- 现象：作品目录同时存在 `会话追踪.txt` 和 `小红书文案.txt` 时，手机点击文案卡片复制了母版 URL、分支 URL、账号等会话元数据。
- 根因：Android `WorkRules.chooseCaption` 与 iOS `WorkScanner` 只优先识别 `文案.txt`，找不到时对全部 TXT 做自然排序；`会话追踪.txt` 因而可能排在真实文案前。点击动作本身正常，错误发生在导入/扫描时。
- 修复：双端统一优先选择 `文案.txt`、`小红书文案.txt`、`抖音文案.txt`，明确排除会话追踪、生产记录、质量报告、标签、元数据和日志类 TXT；仅有元数据的目录不再成为作品。当前生产工作台的结构化记录继续使用 JSON，兼容旧目录不强制改名或删除。
- 证据：Android `testDebugUnitTest`、`assembleRelease`、`lintRelease` 已通过；新增 WorkRules、ZIP 导入和 iOS 扫描回归。真实 K60 覆盖安装与点击剪贴板尚未完成。
- 回归要求：
  1. 同时存在会话元数据与小红书文案时，Android ZIP/SAF/隐藏目录和 iOS 扫描都必须指向小红书文案。
  2. 只有会话元数据与图片时不得识别为可发布作品。
  3. 旧目录只有 `说明.txt`、编号 TXT 等非元数据时仍按兼容兜底识别；不删除现有用户作品。

## DSH-069 移动端提交重试误报“任务不存在”（Android 0.6.61 / iPhone 0.6.44，修复候选）

- 现象：文件已经上传完成，但提交请求遇到一次超时/HTTP 500，电脑用同一个 taskId 重试时，手机返回“任务不存在”；如果手机其实已经导入成功但 ACK 丢失，重试还可能无法补回确认。
- 根因：Android 的提交异常处理删除了 active task 和临时目录；Android/iOS 成功导入后立即移除内存任务，也没有可持久化的完成回执，因此同一任务的重试失去幂等依据。
- 修复：Android 提交失败只记录 `commitFailedAtMs` 并持久化 manifest，保留任务到闲置超时；两端保存最近 128 个完成回执，`GET /v2/tasks/{id}`、重复创建、重复提交都返回完成状态；Android 提交入口串行化。
- 证据：`scripts/verify-task-receipts.mjs`、`verify-p2p-invariants.mjs`、`verify-auto-mobile-update.mjs`、`verify-removed-surfaces.mjs` 和 `git diff --check` 已通过；尚未云端构建或真机验证。
- 回归要求：
  1. 上传完成后人为制造一次提交超时/500，下一次同 taskId commit 必须返回 200，不能返回“任务不存在”。
  2. 导入完成后丢弃首次 ACK，再次 GET/commit 必须返回完成回执，作品库只能增加一次。
  3. 失败提交任务在 30 分钟内可继续提交，超过闲置超时才清理；新任务创建、取消和自动接收开关行为不受影响。

## DSH-068 USB 传送失败没有自动降级到其他通道（Windows V4.3.28，修复候选）

- 现象：USB 连接短暂中断、手机 USB 文件服务拒绝或线缆异常时，电脑提示 USB 失败并直接结束，即使同一手机仍有 Wi‑Fi/P2P/远程中继可用，也要求用户手动重发。
- 根因：`UploadToDevice` 的 USB `catch` 为避免重复直接重新抛出；但 Android WPD 和 iPhone House Arrest 实现都先写入临时目录，失败时会清理暂存，成功后才改名/提交，因此没有必要阻断后续通道。
- 修复：USB 失败且不是用户取消时记录 `usb_upload_failed_fallback`，保留清理后的文件列表，继续使用统一的 Wi‑Fi → P2P → HTTPS 中继链路；用户取消仍立即终止。
- 证据：新增 `verify-p2p-invariants.mjs` 与 `verify-auto-mobile-update.mjs` 断言；云端 Windows 编译和三端发布需由本轮 Actions 完成。当前电脑没有连接手机，真实 USB 回退仍待现场验证。
- 回归要求：USB 成功只产生一次 USB 结果；USB 失败后的临时目录为空且只创建一次后续 Wi‑Fi/P2P/中继任务；P2P 失败后只创建一次 HTTPS 中继任务；用户取消不发生自动回退。

## DSH-066 生产工作区损坏成员记录导致远程设备列表 500（Worker，已修复并部署）

- 现象：当前电脑通过 HTTPS 代理已能访问 Worker，但 `/v1/devices` 返回 HTTP 500，Windows 远程在线轮询无法继续；云端新鲜测试工作区不复现。
- 根因：`listDevices` 假定 `member:` 存储记录始终带完整 `certificate`、签名公钥和协商公钥；历史中断登记或旧版本遗留的损坏记录会抛异常，整页列表失败。
- 修复：遍历成员时校验证书结构；坏记录只写 `invalid_member_skipped` 告警并跳过，保留有效成员的在线、库存、版本和远程权限数据。Worker 已部署版本 `fb0ba8fb-6f15-46f3-b31d-00cbeba36c59`。
- 证据：本地 13 项远程协议测试通过；云端 run `32596690436` 的 remote-relay check 和线上 Worker E2E 全部成功；本机 V4.3.26 通过 HTTPS 代理重启后不再产生新的 `remote_presence_poll_failed`。
- 回归要求：损坏成员不影响有效设备列表；有效手机上线后能被 Windows 轮询发现；远程发送、P2P/HTTPS 回退、ACK 和撤销仍按设备身份工作；后续需真实手机现场验收。

## DSH-067 Windows 远程中继直连 workers.dev TLS 不可达（Windows V4.3.26，已修复）

- 现象：本机直连 Cloudflare `workers.dev` 的 HTTPS 超时，但 Clash Verge 的本地 HTTP CONNECT 代理可正常访问；旧版中控持续记录“中继没有响应”。
- 根因：`RelayHttp` 固定使用 WinHTTP 自动代理；本机 WinHTTP 没有代理设置，未使用已经运行的 Clash 本地端口 `127.0.0.1:7897`。
- 修复：支持 `ZWM_DEVICE_SHARE_RELAY_PROXY` 和 `relay-proxy.txt`；未配置时保持系统自动代理，配置后使用 `WINHTTP_ACCESS_TYPE_NAMED_PROXY`，协议仍是 HTTPS，不降级 HTTP。
- 证据：本机 `curl` 直连失败、经 `127.0.0.1:7897` 的 Worker health 200；云端三端构建、远程中继检查和线上 E2E run `32596690436` 全部通过；V4.3.26 重启后未出现新的远程轮询失败。
- 回归要求：代理不可用时错误可重试、直连可用时不强制代理；HTTPS 证书校验不关闭；真实手机上线后验证设备发现、P2P 直连、HTTPS 回退和 ACK。

## DSH-065 移动端 P2P 完成后未释放 Cloudflare 信令会话（Android 0.6.49 / iPhone 0.6.36，已修复并发布 Beta）

- 现象：移动端 P2P 成功导入并发送 ACK，或失败/取消关闭 PeerConnection 后，认证的 Cloudflare 信令会话仍可能保持；下一轮在线轮询可能再次看到同一会话，造成无意义的重复协商或旧任务重接收。
- 根因：Android/iOS 的统一 `shutdown` 只停止 WebRTC/文件队列，没有同时关闭 `P2PTransport` 信令会话；成功路径和异常路径的资源边界不完整。
- 修复：Android `P2PTransferEngine.shutdown` 与 iOS `P2PTransferEngine.shutdown` 增加 `transport.close()`，让成功、失败、取消都释放信令会话；新增静态不变量断言，版本同步为 Android 0.6.49/versionCode 87、iPhone 0.6.36/build 55、Windows V4.3.25。
- 证据：本地 `verify-p2p-invariants.mjs`、`verify-auto-mobile-update.mjs`、`verify-removed-surfaces.mjs`、13 项远程协议测试和 `git diff --check` 通过；Device Share Hub run `32594831524` 的三端构建、remote-relay check 和线上 Worker E2E 全部成功；Beta `v0.6.49-beta.1` 已发布。
- 回归要求：
  1. P2P 成功写库并 ACK 后，信令会话、PeerConnection、临时缓存和活动引擎都释放，下一轮轮询不重复接收同一会话。
  2. P2P 失败、取消和 20 秒超时都关闭信令会话，并且 Windows 仍只创建一次 HTTPS 回退任务。
  3. Android/iPhone 真实设备跨网传送小作品，确认 DataChannel 成功不创建中继任务；制造直连失败时只创建一个 HTTPS 中继任务，落库后返回 ACK。

## DSH-064 iOS P2P ICE 候选在 SDP 回调边界丢失（iPhone 0.6.35，已修复并发布 Beta）

- 现象：iPhone 的信令轮询和 WebRTC 回调可能同时处理 ICE 候选；候选恰好在远端 SDP 回调排空队列的窗口到达时，可能既没有入队也没有立即提交，P2P 偶发建连失败并转入 HTTPS 中继。
- 根因：`pendingCandidates` 与 `remoteDescriptionSet` 没有共享同步边界；此前 Android 已有 `iceLock`，iOS 仍直接读写。
- 修复：iOS 使用 `NSLock` 将“标记远端 SDP 已就绪、复制并清空待处理候选”和“新候选入队/立即提交”串行化；提交候选放在解锁后执行，避免锁内调用 WebRTC。
- 证据：本地 P2P 不变量脚本、自动更新/库存脚本、隐私表面检查和 13 项中继协议测试通过；云端 run `32593184579` 的三端构建、remote-relay check 和线上 Worker E2E 全部成功；Beta `v0.6.48-beta.1` 已发布。实体 iPhone P2P 验收仍待连接。
- 回归要求：
  1. SDP 成功前后并发到达的 ICE 候选每个都最终提交一次。
  2. P2P 成功 ACK、失败关闭、HTTPS 回退和重复 transferId 去重不受锁影响。
  3. iPhone 真实设备跨网络发送小作品，确认 DataChannel 成功时不创建中继任务；制造直连失败时仍只创建一个 HTTPS 中继任务。

## DSH-063 Beta 已发布但手机更新按钮读不到 Beta 索引（Android/iOS 0.6.47/0.6.34，已修复）

- 现象：Beta 安装包和 Release 已发布，但 Android/iOS 更新器只读取稳定索引；测试机点击“检查更新”时看不到 Beta，iOS 也会复制稳定 AltStore 源。
- 根因：更新通道没有持久化选择，客户端端点固定为 `latest.json`/`altstore.json`，发布 Beta 不会改变稳定入口。
- 修复：设置新增稳定版/测试版通道；Android 测试版读取 `latest-beta.json`，iPhone 测试版使用 `altstore-beta.json`；默认保持稳定版，测试版不污染稳定索引。
- 证据：Android/iOS/Windows 云构建和 remote-relay/Worker E2E run `32590760825` 全部成功；两个公开索引已 API/Raw 复核为 Android 0.6.47/versionCode 85、iPhone 0.6.34/build 53。
- 回归要求：稳定通道仍读稳定索引；测试通道能发现 Beta；Android 下载前校验 SHA-256；iPhone 复制 Beta 源后由 AltStore 更新；切回稳定版后不显示 Beta。真实手机点击和安装仍需现场验收。

## DSH-062 远程在线被旧 Wi-Fi 路由遮蔽（Windows 4.3.23，已修复）

- 现象：同一台手机先被局域网发现、后通过中继在线时，远程心跳刷新了整体 `lastSeen`；发送路径只看非空 IP，可能继续连接已经失效的局域网地址，无法进入 P2P/HTTPS 回退。
- 根因：局域网探测和远程中继共用一份设备快照与时间戳；任一来源直接替换另一来源，无法表达“Wi‑Fi 已过期但远程仍在线”。
- 修复：增加独立 `wifiLastSeen`；局域网 UDP/主动探测与中继记录改为字段合并；发送只把 35 秒内的 Wi‑Fi 观察当作直连，过期时自动使用远程通道。
- 证据：本地源码回归门禁、13 项远程协议测试、隐私表面检查通过；run `32589239907` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta `v0.6.46-beta.3` 已发布。实体手机仍需现场验证。
- 回归要求：局域网在线优先 Wi‑Fi；局域网过期但中继在线时走 P2P/HTTPS；中继离线且 Wi‑Fi 过期时不发送；两种来源轮询交错时保留库存、凭证和通道状态。

## DSH-061 远程缺失库存覆盖局域网完整库存（Windows 4.3.21）

- 现象：同一台手机同时通过局域网和远程中继在线时，远程心跳没有分类库存字段；RelayLoop 把 `-1`/空版本覆盖了局域网已经拿到的精准库存，自动补货可能漏发。
- 根因：远程设备合并逻辑把“字段缺失”当成了“新值未知”，没有按字段保留更完整的本地来源。
- 修复：只有远程字段带合法值时才覆盖对应本地字段；远程缺字段时保留局域网库存、版本和更新能力。新增源级回归断言。
- 证据：Device Share Hub run `32587368303` 的三端构建、remote-relay check 和线上 Worker E2E 全部通过；Beta `v0.6.46-beta.2` 已发布；实体设备自动补货仍待现场验收。
- 回归要求：局域网精准 4、远程字段缺失时仍按 4 判断；远程精准 5 时不补；旧手机和新手机字段切换不抹掉已有完整库存。

## DSH-060 远程中继在线但没有精准库存，自动补货漏掉远程设备（Windows 4.3.20，已修复）

- 现象：手机通过 Cloudflare 远程心跳在线时，电脑只能看到在线/设备名；远程设备没有局域网 IP，因此右键发送、自动更新候选和精准低于 5 自动补货都被旧的 Wi-Fi/USB 判断排除。
- 根因：手机远程心跳原来发送空 JSON；Durable Object 丢弃心跳内容；Windows RelayLoop 只合并在线状态，且多个发送入口各自重复实现局域网判断。
- 修复：Android/iPhone 心跳上报并持久化合法库存与版本字段；Windows 合并远程库存并统一使用 USB/Wi-Fi/远程三路可用判断。缺失分类库存仍按未知处理，不使用总数替代精准流量。
- 证据：remote-relay 协议测试新增库存字段断言并通过；Device Share Hub run `32586026767` 的三端构建、remote-relay check 和线上 Worker E2E 全部通过；Beta `v0.6.46-beta.1` 已发布。实体手机跨网传送、落库、ACK 和精准低于 5 自动补货仍待现场验收。
- 回归要求：远程设备上报精准 4 时自动补 1 个精准作品；上报精准 5 时不补；只上报总数或旧版没有分类字段时不补；P2P 失败仍回退 HTTPS 中继。

## DSH-059 自动补货只扫作品库第一层导致精准源为空（Windows 4.3.19）

- 现象：当前电脑的精准作品实际位于 `成品库\\微信公众号\\作品集_048[转]` 等子目录；自动补货函数只遍历 `library_path` 第一层，因此找不到任何精准源，即使设备精准库存低于 5 也不会发送。
- 根因：`PickAutoRestockSource` 使用 `directory_iterator`，没有覆盖生产库按平台分桶的目录结构。
- 修复：改用 `recursive_directory_iterator`，继续只接受目录名包含 `[转]` 或 `【转】` 且同时包含图文内容的作品文件夹；泛流量目录仍不可作为替代源。
- 证据：现场库扫描确认存在 `微信公众号\\作品集_048[转]` 等有效精准目录；源码回归门禁新增递归扫描断言；Device Share Hub run `32583765953` 和 Beta `v0.6.45-beta.3` 已通过发布验证；实体设备发送仍待连接。
- 回归要求：递归目录下精准库存低于阈值时可进入自动补货；精准库存未知或达到阈值时不发送；只有泛流量目录时不发送。

## DSH-058 自动补货默认关闭导致自动分发不触发（Windows 4.3.18）

- 现象：当前电脑内容数据库只有目录设置，没有 `auto_restock_enabled`；虽然设备精准库存低于阈值，自动补货逻辑仍因严格要求值为 `1` 而直接返回。
- 根因：设置表没有默认迁移，首次使用或旧数据库升级后，UI 与自动分发逻辑没有共同的默认值。
- 修复：ContentStore 初始化用 `INSERT OR IGNORE` 写入自动更新开启、自动补货开启、精准阈值 5；用户已经明确保存的 `0` 或自定义阈值不会覆盖。当前电脑数据库已同步并留有临时备份。
- 证据：`verify-auto-mobile-update.mjs` 已增加默认配置断言；本地数据库读取确认三个设置值为 `1/1/5`；Device Share Hub run `32581609531` 的 Windows 构建通过，Beta `v0.6.45-beta.2` 已发布；真实手机自动补货仍待连接验收。
- 回归要求：新库默认触发自动分发；用户关闭自动补货后不再触发；精准低于 5 才进入补货，泛流量不能替代精准库存。

## DSH-057 自动手机更新失败后永久抑制重试（Windows 4.3.17）

- 现象：Windows 自动更新在传输开始前就把 auto_mobile_update_sent_<deviceId> 写入持久化设置；如果手机离线、HTTP 接收器超时或传输失败，下一次上线仍会被这个版本记录拦截，无法再次推送。
- 根因：把“尝试发送”当成了“已送达”，且失败路径没有清除持久化标记；现场旧版 Android 设备广播在线但接收端口无响应时会触发这一风险。
- 修复：UploadToDevice 返回明确成功值；自动更新仅在成功返回后写入 auto_mobile_update_delivered_<deviceId>。失败只清除本次内存占位并记录 auto_mobile_update_failed_retryable，保留下一轮重试。
- 同轮增强：Android/iPhone 在线信标追加可选精准、泛、未分类库存字段；Windows 解析尾字段，/v2/info 短时不可用时仍可区分“未知”与“精准为 0”，不把总数冒充精准库存。
- 证据：源级回归脚本 tools/device-share-hub/scripts/verify-auto-mobile-update.mjs 通过；Device Share Hub run `32579462284` 的 Android、iOS、Windows、remote-relay check 和已部署 Worker live E2E 全部通过；Beta `v0.6.45-beta.1` 已发布并完成三包哈希核对。实体设备回归仍待现场执行。
- 回归要求：
  1. 更新传输失败后，同一设备/版本下一次上线仍可再次触发；成功传输后才抑制重复推送。
  2. Android/iPhone 旧版不带尾字段时，Windows 保持未知，不回退到总作品数；新版带尾字段时精准库存按 conversion 读取。
  3. 普通作品传送、P2P/HTTPS 中继、更新包校验和用户确认安装流程不受影响。

## DSH-056 P2P ACK 丢失后的中继回退重复入库（Android 0.6.44 / iPhone 0.6.31）

- 现象：P2P 已经把作品写入手机作品库，但 ACK 在回电脑途中丢失时，Windows 会按设计切换到 HTTPS 中继；Android 原来的中继收件路径没有先检查持久化的 `remoteImportedTransfers`，会再次下载并写入同一批作品。
- 根因：Android 只有 P2P 导入路径检查 `wasRemoteImported`，普通中继路径只在导入后写入标记，没有把“已导入、只补 ACK”作为重试分支。
- 修复：Android 在中继下载前检查持久化 transfer 标记；已导入任务只调用 ACK、清理临时缓存和内存占位，不再创建作品。成功 ACK 后同步移除 `remoteInboxTasks`；Android/iOS 的重复 P2P 完成路径也会释放活动引擎/缓存。
- 证据：本地隐私回归、remote-relay check/typecheck、13 项协议测试和源码不变量检查通过；Device Share Hub run `32575362864`、线上 Worker E2E job `97036844830`、Repository quality `32575362946` 和 Secret scan `32575362919` 全部通过；Beta `v0.6.44-beta.1` 已发布并完成三包哈希核对。真实手机回退实传仍待现场验收。
- 回归要求：
  1. P2P 导入成功但 ACK 丢失后，HTTPS 回退最多补 ACK 一次，作品库不增加重复作品。
  2. 普通中继首次接收仍按“下载 → 校验 → 写库 → ACK”顺序执行，任何失败都保留重试机会且不提前 ACK。
  3. Android/iOS 重复 transferId 不留下活动 P2P 引擎、临时缓存或永久收件占位。

## DSH-055 Windows 中继失败后遗留未完成任务（Windows 4.3.16）

- 现象：Windows 已创建远程中继任务后，如果上传、提交、进度读取或本地文件哈希阶段失败，原实现直接返回失败，R2 临时对象和收件箱任务要等 TTL 才清理；同一个失败 `transferId` 也可能继续干扰后续重试。
- 根因：`SendPlainTransfer` 的异常路径没有统一回收已创建的 transfer；正常提交失败与本地异常没有共享取消闭包。
- 修复：记录当前活动 `transferId`，所有上传/提交/进度/哈希异常先 best-effort 调用 `/cancel`，随后清空输出 ID；`RelayHttp` 构造异常也保持在捕获范围内，避免把失败任务留在云端。
- 证据：Device Share Hub run `32571763937` 的 Windows、Android、iOS、remote-relay 和正式 Worker E2E 全部通过；Beta `v0.6.43-beta.1` 已发布。真实设备断网/中断回归仍待现场执行。
- 回归要求：
  1. 任务创建后任一上传、提交、进度或哈希失败都发送一次取消，并清理 R2/收件箱状态。
  2. 失败返回不保留可误用的 `transferId`，下一次重试可以创建独立任务。
  3. 正常 P2P 成功、P2P 回退 HTTPS、局域网/USB 传送和成功 ACK 不受取消清理影响。

## DSH-054 Android P2P ICE 候选在 SDP 回调竞态中丢失（Android 0.6.42）

- 现象：信令轮询收到 ICE 候选时，如果远端 Description 成功回调正在排空待处理队列，候选可能在排空之后才写入旧队列；该候选不再被提交给 PeerConnection，直连可能失败并回退 HTTPS 中继。
- 根因：poller 与 WebRTC 回调线程分别读写 ArrayList pendingIce，remoteDescriptionSet 的判断和队列排空不是一个原子操作。
- 修复：增加 iceLock；远端 Description 成功时在锁内设置状态、复制并清空队列；新候选在锁内判断状态，未就绪则入队，就绪则在锁外立即提交。
- 证据：源码并发审计；Android 云构建与同一提交的 iOS/Windows/remote-relay run 32565416952 已通过，真实设备跨网操作仍待补齐。
- 回归要求：
  1. Description 成功前后并发到达的 ICE 候选都必须最终提交一次。
  2. P2P 连接失败、取消、ACK 成功和 DataChannel 关闭不重复回调或留下活动引擎。
  3. HTTPS 中继回退、局域网/USB 传送、作品库写入和重复 transferId 去重不受影响。

## DSH-053 Android P2P 共享状态缺少跨线程可见性（Android 0.6.41）

- 现象：P2P 引擎同时由 WebRTC 回调、信令轮询、文件处理队列和连接超时任务访问；在 Java 内存模型下，某个线程可能看不到最新的 `channel`/`finished` 状态，出现活连接误触发超时、已结束连接继续处理或重复清理。
- 根因：这些字段是普通非 `volatile` 引用/布尔值，跨线程读写没有明确的 happens-before 关系。
- 修复：将 `peer`、`channel`、`finished` 和 `remoteDescriptionSet` 声明为 `volatile`；保持已有失败回退、ACK 刷新和活动 map 清理逻辑不变。
- 证据：本地源码审计与远程中继测试；需以同一提交的 Android/iOS/Windows 云构建和真实设备 P2P/回退操作补齐最终证据。
- 回归要求：
  1. 正常 P2P 建连后，20 秒超时任务不会因读取旧 `channel` 状态误触发。
  2. 失败、取消、ACK 成功和 DataChannel 关闭并发发生时，不重复调用监听器或留下活动引擎。
  3. HTTPS 中继回退、局域网/USB 传送、作品库写入和重复 `transferId` 去重不受影响。

## DSH-052 Android Manifest 残留媒体读取权限（Android 0.6.40）

- 现象：自动截图采集、截图观察器、悬浮窗和自动剪切板链路已经删除，但 Manifest 仍声明 `READ_MEDIA_IMAGES` 与 `READ_MEDIA_VISUAL_USER_SELECTED`，与当前隐私边界不一致，可能继续触发厂商权限/风险提示。
- 根因：旧截图/媒体导入阶段的权限声明没有随功能删除一起清理；源码中当前没有对应的媒体读取调用。
- 修复：删除两项 Android 13+ 媒体读取权限；保留仅用于 Android 10 隐藏作品兼容导入的 `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` 声明和用户主动 SAF 文件夹授权。三端版本同步提升。
- 证据：本地 Manifest 与源码静态检查；随后以同一提交的 GitHub Actions Android/iOS/Windows/remote-relay 构建、Release 包 Manifest、权限列表和真实设备安全扫描作为最终证据。
- 回归要求：
  1. Release 合并 Manifest 不包含 `READ_MEDIA_IMAGES`、`READ_MEDIA_VISUAL_USER_SELECTED`、`SYSTEM_ALERT_WINDOW`，也没有截图观察器、悬浮窗组件或自动剪切板读取。
  2. Android 10 用户主动选择作品文件夹后，隐藏作品兼容导入、普通文件/图片接收和清理逻辑不回归；Android 11+ SAF 文件夹流程不回归。
  3. Android/iOS/Windows 云构建、更新索引、Beta 资产和桌面包哈希一致；真实设备安装后复测厂商安全扫描。

## DSH-051 Android P2P 启动失败会残留活动引擎（Android 0.6.39）

- 现象：Android 创建 P2P PeerConnection 失败时，`accept()` 仍返回已经结束的引擎，`OnlineService` 将它放进 `p2pEngines`；同一会话后续轮询会被“已存在”条件跳过，无法重试。
- 根因：Android `accept()` 与 iOS 已有的“启动后若 finished 则返回 nil”保护不一致；取消路径也可能在执行队列已关闭后再次提交任务。
- 修复：Android `accept()` 在启动同步失败时返回 `null`，调用方不缓存空引擎；取消提交增加已关闭队列保护，失败会话由下一轮收件轮询重新接管。
- 证据：GitHub Actions run `32558264352` 的 Android、iOS、Windows、remote-relay 全部通过；安全扫描 run `32558264343`、质量检查 run `32558264394` 通过；Beta 发布页为 `v0.6.39-beta.1`。实体手机业务回归仍未完成。
- 回归要求：
  1. 模拟 PeerConnection 创建失败时，Android 不把已结束引擎放入 `p2pEngines`，下一次轮询可以再次尝试同一会话。
  2. 停止接收服务、P2P 失败和 ACK 后延迟关闭都不得因重复 `cancel` 抛异常。
  3. 正常 P2P、20 秒建连超时、HTTPS 中继回退、局域网/USB 传送和重复 `transferId` 去重保持不变。

## DSH-050 移动端 P2P 收件建连等待无界与 WebRTC 回调阻塞（Android 0.6.38 / iPhone 0.6.25）

- 现象：发送端创建会话后没有真正建立 DataChannel 时，Android/iOS 收件端可能长期等待；Android 直接在 WebRTC 数据回调里执行文件写入和 SHA-256 校验，大文件可能阻塞回调线程。
- 根因：移动端收件端缺少独立的建连超时；Android 没有把 WebRTC 回调收到的 ByteBuffer 与后续文件 I/O 解耦。
- 修复：两端增加 20 秒建连超时，超时关闭 P2P 并回退 HTTPS 中继；Android 立即复制 WebRTC 所有权的数据帧，再交给串行队列执行写入、大小校验、SHA-256 和作品库导入。
- 证据：GitHub Actions run `32556181728` 的 Android、iOS、Windows、remote-relay 全部通过；安全扫描 run `32556181727`、质量检查 run `32556181732` 通过；Beta 发布页为 `v0.6.38-beta.1`。实体手机业务回归仍未完成。
- 回归要求：
  1. 发送端不发起 DataChannel 时，Android/iOS 最迟 20 秒结束 P2P，并由 Windows 只创建一个 HTTPS 中继任务。
  2. 大文件连续分片传输时，WebRTC 回调线程不执行阻塞文件 I/O；manifest、大小、SHA-256、作品库写入和 ACK 顺序保持不变。
  3. 正常慢速 P2P、局域网/USB 传送、重复 `transferId` 去重和失败会话关闭不受影响。

## DSH-049 移动端 P2P 失败未及时关闭信令会话（Android 0.6.37 / iPhone 0.6.24）

- 现象：Android/iOS P2P 接收失败时会清理本地引擎，但 Cloudflare 控制面会话仍保持开放，直到 2 分钟 TTL 才回收；发送端虽然会回退 HTTPS，中间会留下脏会话。
- 根因：移动端 `SignalTransport` 只有快照和发信令接口，失败回调没有 `/close` 动作；同步关闭还可能阻塞 WebRTC 失败处理线程。
- 修复：Android/iOS 为 `SignalTransport` 增加异步 `close`；P2P 失败路径立即排队关闭对应会话，保留本地清理和 HTTPS 回退，不阻塞失败回调。
- 证据：GitHub Actions run `32553627094` 的 Android、iOS、Windows、remote-relay、质量检查和安全检查全部通过；Beta 发布页为 `v0.6.37-beta.1`。实体手机业务回归仍未完成。
- 回归要求：
  1. Android/iOS 在 manifest、数据帧、校验或作品库写入失败后，控制面会话进入 `closed`，不能只等 TTL。
  2. 关闭请求不得阻塞 WebRTC 失败回调；HTTPS 回退仍只创建一个任务。
  3. 成功 ACK、局域网/USB 传送和正常慢速 P2P 不受影响。

## DSH-048 P2P 异常回退留下开放信令会话（Windows 4.3.9）

- 现象：Windows 已创建 P2P 会话后，如果文件校验、信令或 DataChannel 发送路径抛出异常，外层回退逻辑会继续创建 HTTPS 中继任务，但原 P2P 会话只能等 2 分钟 TTL 才清理。
- 根因：正常返回路径有 `/close`，异常直接跳到外层 `catch`，没有覆盖“会话已创建、传输尚未正常返回”的范围。
- 修复：在 `TryP2PTransfer` 创建会话后建立统一 `closeSession` 清理闭包；成功、P2P 返回失败和内部异常都先关闭会话，再把失败交给上层 HTTPS 中继回退。
- 证据：GitHub Actions run `32551407594` 的 Windows portable、Android、iOS、remote-relay、validate 和两项 secret scan 全部通过；Beta Windows 资产 SHA-256 为 `544a823df94ce0232e98a96e5cc4a1b1ad7398508ff3dcf049ab93b6b12df169`。
- 回归要求：
  1. 文件哈希、信令和 DataChannel 任一阶段抛异常时，控制面会话都进入 `closed`，不能只等 TTL。
  2. P2P 失败后只创建一个 HTTPS 中继任务，不能因为旧会话残留产生重复发送。
  3. 正常 P2P ACK 和已有局域网/USB 传送不受清理闭包影响。

## DSH-047 P2P 成功尾部 ACK 竞态与背压无限等待（Windows 4.3.9 / Android 0.6.36 / iPhone 0.6.23）

- 现象：手机导入成功后立即关闭 DataChannel，电脑可能在收到 ACK 前看到通道关闭并重复走 HTTPS 中继；iPhone ZIP 导入成功后 P2P 缓存目录可能残留；Windows 发送侧背压长期不下降时没有硬性回退边界。
- 根因：`sendData`/`DataChannel.send` 只把 ACK 放入 SCTP 队列，紧接着关闭 peer 不能证明字节已经发出；成功路径没有统一清理 iOS 缓存；背压循环只看缓冲量，没有停滞计时。
- 修复：Android/iOS ACK 后等待 500ms 再关闭；iOS 成功路径删除临时目录；Windows 记录背压下降时间，连续 20 秒无进展即返回失败，由上层自动切 HTTPS 中继。
- 证据：同一提交的 GitHub Actions Android/iOS/Windows 构建、remote-relay、validate 和 secret scan 已通过；当前没有连接实体手机，因此实体传输回归仍未完成。
- 回归要求：
  1. 小 ZIP 成功直传后电脑只收到一次成功 ACK，不创建中继重复任务。
  2. iPhone 成功导入 ZIP 后，P2P 缓存目录在 ACK 刷新窗口后消失。
  3. 模拟接收端不读数据时，Windows 最迟 20 秒退出 P2P 并自动进入 HTTPS 中继。
  4. 正常慢速传输只要缓冲持续下降，不得被 20 秒停滞保护误切换。

## DSH-046 远程传送只有信令没有文件数据面（Windows 4.3.8 / Android 0.6.35 / iPhone 0.6.22）

- 现象：Cloudflare 的 P2P 会话可以创建并交换 SDP/ICE，但此前没有真正的 DataChannel 文件收发；如果误把信令成功当成文件成功，会出现“已直连但手机没有作品”。
- 根因：远程中继控制面和 WebRTC 信令已先完成，三端原生 DataChannel 没有接入同一套 manifest、分片、完整性校验和 ACK 协议。
- 修复：Windows 接入 libdatachannel 发起 `album-transfer-v1`；Android 接入 Maven Central WebRTC SDK；iPhone 接入固定版本 WebRTC XCFramework。发送端按 48 KiB 分片，接收端落缓存、校验大小/SHA-256、写入现有作品库后才 ACK。
- 回退：建连 20 秒超时、ICE/DataChannel 失败、数据帧越界、哈希不一致或作品库写入失败均返回失败，由 Windows 自动创建现有 `mode: plain` HTTPS 中继任务；P2P 失败不能记作已送达。
- 证据：本地协议检查通过，远程 Worker 13 项测试通过；三端正式结论必须等待同一提交的 GitHub Actions Windows/Android/iOS 构建。暂无实体 Android/iPhone 跨网络业务证据。
- 回归要求：
  1. 同一 Wi-Fi 与不同网络各发送一个小 ZIP，手机只能在写库成功后收到 ACK。
  2. 接收端断开、篡改分片或制造错误 SHA-256 时，P2P 失败并自动转 HTTPS 中继，作品不重复。
  3. 发送端不能因为 `libdatachannel` 的缓冲返回值为 false 就误判失败；必须等待缓冲并保持分片顺序。
  4. 停止 Android/iOS 接收服务时，P2P 临时文件和轮询线程都要清理，旧局域网/USB 传送不受影响。

## DSH-045 手机端自动截图/剪切板与作品卡片入口收口（Android 0.6.24 / iPhone 0.6.11）

- 现象：手机端不需要自动截图、自动剪切板同步；旧入口和权限让安全扫描产生风险提示，作品卡片的两个纵向按钮也占用过多空间。
- 修复：两端删除自动截图采集、截图中转和自动读取/同步系统剪切板；卡片统一为“预览 / 发抖音 / 发小红书”，预览支持多图翻页。
- 保留边界：用户主动点“复制文案”或“复制诊断信息”时才写入系统剪切板；普通文件/图片传输、导入和系统分享不变。
- 回归要求：云端检查无 `ClipboardBridge`/自动监听、无截图观察器、无 `SYSTEM_ALERT_WINDOW`/`NSPhotoLibraryUsageDescription`；Android/iOS 构建、Release 包结构和两端更新索引一致，最后补真实手机安全扫描与多图点击验收。

## DSH-044 手机端剪切板/截图模块触发权限与风险提示（Android 0.6.23 修复候选）

- 现象：手机端出现悬浮剪切板、自动发送到主设备和截图相关入口；用户怀疑系统持续读取剪切板或悬浮窗导致病毒/隐私风险提示。
- 根因：`OnlineService` 启动和发现设备时维护悬浮窗，读取/写回系统剪切板并注册 `MediaStore` 截图观察器；Manifest 声明 `SYSTEM_ALERT_WINDOW`，还存在截图接收器和剪切板同步接口。
- 修复：删除整套剪切板、截图、悬浮窗和中继代码、设置入口、组件及 `SYSTEM_ALERT_WINDOW`；普通文件/图片传输保留。用户主动“复制并分享”和“复制诊断信息”仍是明确动作，不属于后台自动读取。
- 证据：源码搜索确认后台自动路径已移除；需以 Android 单测、Release/Lint、合并 Manifest 和真机安全扫描结果完成最终验收。
- 回归要求：
  1. Release Manifest 不包含 `SYSTEM_ALERT_WINDOW`、`ClipboardActivity` 或 `ScreenshotSendReceiver`。
  2. 服务启动、设备发现、作品刷新均不访问系统剪切板、不注册截图观察器。
  3. 普通文件/图片传输、作品导入、更新包接收和显式复制分享仍正常。
  4. 真实设备安装后重新执行厂商安全扫描；不能仅凭源码或构建通过宣称“病毒提示已彻底消失”。

## DSH-043 大文件失败后从头传输（Android 0.6.21 修复）

- 现象：电脑向手机发送大文件时网络中断，下一次重试会重新创建任务并从 0 字节开始；重复失败会重复消耗时间和流量。
- 根因：电脑端失败分支直接调用 `/cancel`，手机随即删除 `.receiving` 临时文件；协议没有任务状态查询、已收字节和偏移上传字段。
- 修复：电脑端为同一内容复用稳定 `taskId`，失败后保留可恢复账本；Android 接收端落盘 `task.json`，提供 `GET /v2/tasks/{taskId}`，按 `X-File-Offset` 追加剩余数据，并对合并结果做 SHA-256 校验后再提交。旧接收端继续兼容从头传输。
- 证据：Python 传输技能 32 项单测、语法检查通过；Android Gradle 9.4.1 单测通过，新增偏移范围和断点续传回归。VIVO 真机安装 0.6.21 后仍需补受控中断/续传证据。
- 回归要求：
  1. 大文件上传到中途断开后，手机任务和 `.receiving` 文件保留。
  2. 重试必须先读取 `receivedBytes`，上传请求的 `Content-Length` 等于完整文件大小减去偏移。
  3. 偏移、总长度或 SHA-256 不匹配时拒绝续传，不得提交半份文件。
  4. 完整提交后只能出现一份目标文件；明确取消任务后才清理断点。

## DSH-042 批量发送、传送队列与暗色模式（V4.3.0 修复）

- 现象：DSH-041 遗留的 8 项未修复体验问题中，用户要求一次性完成剩余全部功能。
- 根因：前期迭代聚焦核心传送功能，批量操作、队列管理和视觉个性化未纳入优先级。
- 修复（commit `39eb5f6`，V4.3.0）：
  1. 设备列表从单选改为多选（`LBS_MULTIPLESEL`），用户可点选多台设备。
  2. 新增 `GetSelectedDevices()` 收集所有选中设备，`SendSelectedLibraryItem` 和 `ChooseAndSend` 均支持批量发送。
  3. 批量发送在后台线程顺序执行，状态栏显示"批量传送 N/M：正在发送到XXX"。
  4. 新增"全选设备"按钮，一键选中所有在线设备。
  5. 设备列表刷新时通过 `selectedIds` 集合保持多选状态，不会因刷新丢失选择。
  6. 系统托盘通知（`ShowTrayBalloon`）在 V4.2.2 已实现，传送完成/失败/最小化时弹出气泡。
  7. 素材库搜索和文件大小显示在 V4.2.2 已实现。
  8. 开机自启在 V4.2.2 已实现。
  9. SHA-256 异步进度在 V4.2.2 已实现。
  10. 暗色模式：新增 `ThemeColors` 结构体和亮/暗两套配色，`WM_CTLCOLOR*` 处理静态/列表/编辑控件着色，偏好持久化到内容数据库。
- 证据：commit `39eb5f6` 已推送至 `origin/main`，CI 构建待完成。
- 回归要求：
  1. 多选设备后点"传送选中素材"，每台设备顺序收到文件。
  2. "全选设备"按钮选中所有设备。
  3. 设备列表刷新后已选设备保持选中。
  4. 暗色模式切换后窗口背景、列表、按钮、文字颜色全部正确。
  5. 暗色模式偏好重启后保持。
  6. 单选单发场景不受影响。

## DSH-041 Windows 用户体验问题批次（V4.2.2 修复）

- 现象：用户日常使用角度审查发现 13 项体验问题，其中高影响 5 项、中低影响 8 项。
- 根因：开发阶段只关注技术功能实现（设备发现、文件传送、协议），未在提交前执行用户角度审视清单，导致交互时效、信息可读性和容错体验被忽略。
- 修复（commit `6b86766`，V4.2.2）：
  1. `DEVICE_RETENTION_SECONDS` 从 600 降为 90，离线设备 90 秒后移除，不再让用户对着已断连设备操作。
  2. 右键"发送到"无设备时等待从 20 秒降为 8 秒，超时后提示"请确认手机相册已打开或允许后台接收后重试"。
  3. HTTP 409 错误从技术码改为"手机上还有上一批素材没处理完，请在手机端确认后再试"。
  4. 拖文件到空白区域从 MessageBox 弹窗改为状态栏温和提示"请把文件拖到右侧某台在线手机的卡片上"。
  5. Device 结构体新增 `lastSentTime` 字段，`RecordSuccessfulTransfers` 传送成功时写入时间戳，`DrawDeviceItem` 在设备卡片副标题显示"刚刚传过 / X分钟前传过 / X小时前传过"。
  6. AGENTS.md 新增"从用户角度审视"强制清单，覆盖交互时效、操作步骤、信息可读性、容错恢复和日常体验 5 个维度。
- 证据：commit `6b86766` 已推送至 `origin/main`，CI 构建待完成。
- 回归要求：
  1. 设备离线后 90 秒内从列表消失。
  2. 右键无设备时 8 秒内给出提示。
  3. 409 错误显示人话提示。
  4. 拖文件到空白区域不弹窗，状态栏显示引导文字。
  5. 传送成功后设备卡片副标题显示相对时间。
  6. 每次改代码前必须过一遍"从用户角度审视"清单。
- 未修复项：已在 V4.3.0（DSH-042）中全部修复。

## DSH-040 Windows 热点/非 /24 子网下设备发现失败

- 现象：手机与电脑同处一个 WiFi（或手机开热点、电脑连热点），手机之间能互相发现，但 Windows 中控扫描不到任何在线设备，设备列表和“发送到”菜单均为空。
- 根因：
  1. `ActiveProbeTargets()` 硬编码按 `/24` 子网扫描，当实际子网掩码不是 `/24`（如手机热点通常是 `/28` 或 `/30`，企业网络可能是 `/16`）时，目标 IP 范围计算错误，遗漏真实设备 IP。
  2. 原逻辑要求网卡必须存在默认网关才参与扫描，手机热点模式下电脑侧可能没有传统网关或网关检测失败，导致 0 个扫描目标。
  3. WSL、VMware、Hyper-V 等虚拟网卡的地址也被纳入扫描范围，浪费探测时间且可能命中无关地址。
  4. 未查询 ARP 表，遗漏了系统已知的邻居设备（手机刚通信过但不在子网扫描范围内的 IP）。
  5. `ProbeDeviceHost()` 连接超时仅 400ms、收发超时仅 800ms，热点中转链路延迟较高时无法在超时内完成握手。
- 修复（commit `a981d1f`，V4.2.1 重建）：
  1. `ActiveProbeTargets()` 改用 `OnLinkPrefixLength` 计算实际子网范围，`/24` 以下按 `/24` 扫描、`/24` 以上按实际掩码扫描；移除网关依赖，所有 Up 状态的物理网卡私有地址段均参与扫描。
  2. 新增 `IsVirtualAdapter()` 过滤 WSL/VMware/Hyper-V/VirtualBox/TAP/VPN 等虚拟网卡，`DiscoveryTargets()` 和 `ActiveProbeTargets()` 均调用。
  3. 新增 `AddArpTableTargets()` 调用 `GetIpNetTable2` 查询 ARP 表中 Reachable/Stale/Delay/Probe 状态的邻居 IP，补充到主动探测目标列表。
  4. `ProbeDeviceHost()` 连接超时从 400ms 提升到 800ms，收发超时从 800ms 提升到 1500ms。
  5. `DiscoveryTargets()` 同步增加虚拟网卡过滤。
- 证据：CI 构建 `windows-portable` job 通过，产物 `素材投送中控-Windows-V4.2.1.exe`（852480 字节）已替换旧版（848896 字节）并成功启动（PID=16212）。commit `a981d1f` 已推送至 `origin/main`。
- 回归要求：
  1. 标准 `/24` WiFi 子网下设备发现数量不减少（与 DSH-038 修复前持平或更好）。
  2. 手机开热点、电脑连热点的 `/28` 或 `/30` 子网下能发现手机设备。
  3. WSL/VMware 虚拟网卡不参与扫描，不产生无关探测。
  4. ARP 表中有但子网扫描范围外的设备 IP 能被探测到。
  5. 热点中转高延迟环境下 800ms 连接超时 + 1500ms 收发超时能完成握手。
  6. “发送到 → 相册在线设备” 右键菜单在有设备时正常弹出设备列表。

## DSH-039 Windows“发送到”子文件夹误触发文件复制

- 现象：用户右键选择“发送到 → 相册在线设备”后，Windows 显示正在把所选内容复制到另一个文件夹，没有弹出设备选择；重复操作会持续占用系统盘空间。
- 根因：Windows 的 `SendTo` 目录不支持用普通子文件夹实现级联设备菜单；该子文件夹本身会被 Shell 当作复制目标。
- 修复：V4.2.1 只在 `SendTo` 根目录创建“发送到相册设备.lnk”，参数不预选设备；中控收到原路径后弹出实时在线设备列表，选定后调用既有上传协议。旧目录只清理自有 marker/快捷方式，未知内容不自动删除。
- 实体证据：误复制副本为 45,646,452 字节，与原件 SHA-256 `751BE9E72B019D180AFFD374B0778097183BD140DA84C1B31A0B2E20A126C42D` 一致；副本已移入回收站，原件仍在。V4.2.1 根快捷方式已验证不再把文件写入 `SendTo`；90 字节测试文件通过当前 USB Xiaomi 15 发送、手机回读，源与回读 SHA-256 均为 `D2EB3651251EE5BE612C596B2231FCCA181C321C3194ACD8B385E3E6F83A8EFC`。电脑端真实完成状态已显示 100% 和成功结果；新版浮层改为短文字提示，不保留常驻进度小窗。
- 回归：右键入口不得是目录；点击必须先展示设备选择；取消不得发送；没有设备应扫描并提示；成功后 `SendTo` 不得出现用户文件；文件和文件夹传送临时缓存必须按既有规则清理。
- 二次候选检查：选择菜单不能阻塞 `WM_COPYDATA` 的 5 秒转交窗口；主进程必须先确认接收请求，再异步弹设备菜单，防止用户仍在选择时辅助进程误报失败。
- 三次实体反馈：右键纯发送不得调用 `CheckTransferHistory`，否则大文件夹会长时间停在“正在核对是否传送过”且尚未创建传送任务。新版跳过历史指纹筛选，立即进入 USB/V2 发送，并在主窗口显示进度、结束时弹结果。

## DSH-038 手机在线但 Windows 新启动后设备列表和“发送到”菜单为空

- 现象：同一局域网内至少一台手机接收端在线，共享传送技能通过主动探测可以找到，Windows V4.2.0 首轮候选只依赖 UDP 时却记录 `online=0`，资源管理器中不生成“相册在线设备”。
- 根因：部分路由器会抑制 Wi‑Fi 广播回复；首轮主动探测又漏传 `GAA_FLAG_INCLUDE_GATEWAYS`，导致要求默认网关的筛选拿不到网关数据并生成 0 个目标。补齐目标后，WinHTTP 多地址并发在实体局域网仍出现漏检。
- 修复：保留 UDP 即时发现，同时读取默认网关所在私有 `/24`，使用带 400ms 连接截止时间的原生局域网 socket 并发请求 `/v2/info`；只接受 protocol 2，每 15 秒刷新并把 35 秒内确认的设备同步到“发送到”菜单。没有依赖 Python、ADB、手工 IP 或管理员权限。
- 证据：修复前共享技能真实发现在线手机而 Windows 日志为 `send_to_synced online=0`；最终候选记录 `targets=253 devices=1`，创建真实设备 `1号｜公司｜红米13.lnk`。合成接收端验证资源管理器参数、单实例 IPC、35 字节文件传送和 commit，源/接收 SHA-256 均为 `FA795428874E4CAFE1860A706827964F3C214E6302658A4CEF6F8DBE9B3F7C1B`；合成端停止超过 35 秒后快捷方式自动移除，真实在线设备保留。
- 回归要求：广播正常时不得产生重复设备；广播受限时主动探测应在一次周期内补齐；不扫描无默认网关的 WSL/VMware 链路；离线设备从快捷菜单移除，但 USB 仍可用时保留。

## DSH-037 USB 连接后电脑能看到手机、手机看不到电脑

- 现象：手机连接数据线后，Windows 中控能枚举手机，Android 传送页却没有电脑。
- 环境证据：连接时手机与电脑处于不同 IPv4 子网，Windows 仅有手机 USB/调试设备，没有 RNDIS/NCM USB 网卡。Android 0.6.13 的设备发现因此没有任何可达电脑地址。
- 根因：MTP 是 Windows 主机主动访问手机存储，不会让手机 App 获得一个可写的 Windows 文件目标；手机端原界面又把所有可达通道固定标成 WiFi，没有解释或选择 USB 网络共享。
- 修复：0.6.14 传送页增加自动/USB/Wi‑Fi 选择、USB 网络共享设置入口与通道标签；发现逻辑按远端地址匹配本机接口子网，并把 `rndis/usb/ncm/tether` 接口识别为 USB。真正传送继续使用 V2 HTTP、进度、SHA-256 和 commit 回执。
- 安全边界：不使用 ADB、Root、手动 IP、配对码或自定义 Windows 驱动。普通 MTP 保持电脑→手机；手机→电脑使用用户明确开启的系统 USB 网络共享。
- 实体证据：同一手机覆盖 0.6.14/code 52 后，USB 网络共享建立 `rndis0` 与 Windows Remote NDIS 同子网；手机列表显示电脑 `USB`。48 字节测试文件分别完成手机→电脑、电脑→手机 commit，两个接收端 SHA-256 均为源哈希，测试副本已清理。
- 回归：连接数据线但未开 USB 网络共享时，选择 USB 应显示可行动说明；开启后 Windows 出现 USB 网卡，手机列表显示电脑 `USB`；双向分别传一个测试文件并核对字节与 SHA-256，关闭共享后 USB 设备自然离线，Wi‑Fi 设备不应被误标。

## DSH-036 Android 下载后自动安装页被系统拦截

- 最终产品取舍（0.6.14）：用户明确不要“允许来自此来源”授权，接受由系统下载器和系统通知完成安装。0.6.14 因此删除应用自有下载服务、私有 APK 安装 Activity 与 `REQUEST_INSTALL_PACKAGES`，恢复 DownloadManager；应用只在系统完成广播后按任务 ID 与 SHA-256 核对。
- 兼容边界：此前 Redmi Note 8 的 MIUI/迅雷内核曾对 GitHub 下载返回状态 700，所以系统下载不承诺在所有厂商网络下一次成功；失败时由系统界面重试或改用公开 APK。该边界是用户知情后的权限优先取舍，不再通过增加安装来源权限规避。

- 现象：应用自动下载并验证更新包后尝试直接进入安装器，但部分 Android/MIUI 设备禁止由下载完成广播自动拉起界面，用户只看到下载完成却没有安装页。
- 根因：普通应用的后台 Activity 启动受到 Android 与厂商系统限制；即使进程可见性判断通过，下载完成广播也不等于一次用户授权的界面跳转。
- 深层兼容根因：真机公网回归进一步发现下载接收器声明为 `exported=false`，MIUI 把系统下载服务视为外部 UID，直接拒绝投递受保护的 `DOWNLOAD_COMPLETE` 广播；因此文件即使下完，应用也无法进入校验和安装提醒。
- 下载根因：接收器修好后，Redmi Note 8 的 MIUI 系统下载器仍把 GitHub 请求交给迅雷内核，日志返回 `ResponseCode=150 / status=700` 并取消任务；不是 APK 内容或系统安装器解析失败，而是系统下载阶段没有获得完整文件。
- 修复：打开应用仍自动检查，但发现新版先由用户确认“下载更新”；确认后由应用前台下载服务执行 HTTPS 下载、有限跳转、断点续传和常驻进度。校验完成后在首页弹出版本、APK 文件名和大小，并由用户点击“安装”。应用不在首页时保留通知，下次返回再次提示；不再强行自动启动安装器。
- 首轮实体证据：已安装修正版 0.6.11 的 Redmi Note 8 从公网发现 0.6.12，确认后没有再出现 MIUI/迅雷提示；应用通知持续显示进度，约 24 秒后完成下载。随后 MIUI `PackageManager` 对可解析、v2 签名有效的 APK 返回空新版签名字段，校验器误报“安装包没有签名”；同时移动网络访问 raw 更新索引多次超时。
- 二次修复：0.6.13 改用 GitHub Release API 并精确读取同一 Release 的 `SHA256SUMS.txt`；签名校验同时请求新旧字段，新版字段为空时回退 `PackageInfo.signatures`。设备随后从 ADB 断开，二次真机校验与点击安装页待重新连接，不提前写为通过。
- 回归要求：拒绝下载不得创建任务；确认后只能出现系统下载任务和系统通知；正式 APK 不得包含 `REQUEST_INSTALL_PACKAGES`、应用自有下载服务或安装 Activity；文件名必须带 `.apk`；后台文件传送不得因更新流程修改而停止。

## DSH-035 Android 分类遮字、下拉无状态及厂商开关变形

- 现象：Redmi Note 8 首页文件夹入口和其余操作挤在右侧；分类选中白块盖住黑字；下拉只有阻尼位移，没有释放阈值、旋转加载和完成反馈；设置中的系统 Switch 在 MIUI 上滑块超过轨道，视觉像被截断；自动发送截图也没有目标设备选择入口。
- 根因：标题行先放了占满剩余宽度的标题再放模式按钮；分类指示块绘制层级高于文字且宽度未扣除内边距；滚动容器只暴露完成回调；Android 原生 Switch 会继承厂商尺寸和绘制规则；截图目标偏好已有协议字段但设置页没有可操作入口。
- 修复：模式入口提前到标题行左侧；分类文字层提升并按可用内宽四等分；下拉状态机暴露拉动、触发、刷新与复位；开关使用应用自行绘制的完整胶囊和 180ms 动画；主设备选择只列可信设备并显示在线、离线、未选择。
- 证据：Android 21 个测试套件共 65 项单元测试、Release 构建和 Release Lint 通过；Redmi Note 8 实际覆盖安装 0.6.8/code 46，首页、分类切换、完整下拉刷新、设置开关和无在线设备的主设备选择弹窗均完成 ADB 操作与截图/录屏检查。
- 回归要求：在 320/360dp、系统大字体、横竖屏和至少两个厂商系统检查开关轨道完整；拉动不足阈值必须复位，超过阈值只刷新一次并显示真实结果；分类滑块不得遮住任何标签；自动发送启用但无目标时必须要求选择，不能静默假开启。

## DSH-034 红米应用内下载后提示“解析包错误”

- 现象：红米 Note 上从 Android 0.6.3 的应用内更新入口下载新版后，系统安装器提示解析包错误；云端原 0.6.3 APK 的大小、包名、最低系统版本和 v2 签名本身可解析。
- 根因判断：故障更符合下载副本未完整写入、厂商下载器没有保留 `.apk` 扩展名，或下载完成通知在应用完成校验前先把半成品交给安装器；当前没有连接故障手机取得系统安装器错误码，因此不把推断写成已证实的唯一根因。
- 修复：目标文件名统一为纯英文 `album-Android-<version>.apk`；系统下载完成后先核对 SHA-256，再复制到应用更新目录并检查最小大小、APK 可解析性、包名、版本号、版本码和签名证书。验证完成前不发布可安装通知，验证失败删除下载任务并允许重下；打开安装器失败时提供系统下载列表兼容入口。
- 自动证据：0.6.4/code 42 的 64 项 Android 单元测试、Release 构建与 Release Lint 全部通过；候选 APK 可被 Android build-tools 解析，包名 `com.zwm.gallery`、versionCode 42、versionName 0.6.4，v2 签名证书与既有升级链一致。
- 实体回归：红米先安装公开 0.6.3，通过启动自动更新完整走到 0.6.4；下载中途断网后恢复，核对只有一个英文 `.apk`、校验完成后才出现安装入口、覆盖安装保留作品和设置。若仍失败，保存应用诊断中的下载/校验阶段和系统安装器返回原因。

## DSH-033 长剪切撑满悬浮窗并截断固定常用语

- 现象：最新剪切是一大段文字时，内容按钮按完整高度展开，把下方固定常用语挤出悬浮窗口；固定话术自己的内层滚动区域拿不到足够高度，用户下滑也无法到达。
- 根因：最新剪切位于滚动区域之外且没有最大行数，固定话术又单独嵌套一个 ScrollView，父子高度和触摸手势互相竞争。
- 修复：长剪切默认显示 3 行并省略，独立“展开/收起”按钮固定在最新剪切标题行；最新剪切与固定话术改为同一个 ScrollView，普通 ClipboardActivity 同步移除双层嵌套滚动。新内容到达时自动收起。
- 自动证据：新增短文本、3 行、4 行、141 字符折叠边界，以及悬浮球临时关闭截止时间和溢出保护测试；Android 62 项单元测试、Release 构建与 Release Lint 已全部通过。
- 实体回归：分别使用 1 行、3 行、4 行、超长单段和大量换行文本，在默认尺寸与最小悬浮窗尺寸检查省略号、展开、收起、复制，以及下滑到最后一条固定话术。
- 悬浮球实体回归：静止长按不足 5 秒应正常打开，满 5 秒弹出时长；拖动超过阈值不得误弹。分别验证 30 秒、5 分钟、1 天到期恢复，永久关闭后从设置重新开启。

## DSH-032 悬浮剪切板重连漏同步、并发分叉与更新包重复下载

- 现象：手机离线后以原 IP 重新上线可能收不到离线期间修改的话术；点击固定话术只复制不即时同步；接收线程与悬浮窗同时写入时偶发覆盖；旧版 App 在新版 APK 已下载但尚未安装时再次打开，可能重复下载同一版本。
- 根因：设备重现只比较展示字段，忽略上次在线时间；剪切点击没有进入最新值同步链；存储锁绑定单个临时对象而不是共享目录；相同毫秒的冲突缺少确定次序；下载完成后过早清空版本状态。
- 修复：把超过 15 秒后重新出现视为重连并主动补齐；复制话术立即保存和广播；同一进程内所有剪切板存储实例使用共享锁，等时间戳按删除状态和内容确定收敛；剪切板只保留最新物理记录；DownloadManager 的进行中和已完成状态持续跟踪到覆盖安装后的首次启动。
- 自动证据：新增同时间戳双端收敛、最新值物理压缩、跨实例 40 次并发写入和下载状态回归；Android 58 项单元测试、Release 编译与 Release Lint 全部通过。
- 实体回归：两台手机至少离线 20 秒后重新上线，核对话术补齐；在其他 App 复制文字后点悬浮圆点，核对顶部最新值；点固定话术，核对另一台在线手机立即更新；下载 0.6.2 后未安装前重开旧 App，系统下载列表只能有一个同版本 APK。

## DSH-031 普通文件传送误触发作品分享

- 现象：平铺图片到达 Android 后会直接打开发布分享页，用户原本只想像文件夹作品一样存到手机。
- 根因：V2 任务没有表达“落盘”与“直接分享”的意图，接收端只要看到图片就自动分享。
- 修复：任务新增缺省为 `false` 的 `autoShare`；普通文件、文件夹和截图始终落入真实接收文件夹，只有 Android 系统分享目标收到图片和文字时明确设置为 `true`。
- 证据：Android 0.6.0 单元测试、Debug/Release 编译和 Lint；真机系统分享与多设备接收待设备重新连接后逐台补验。
- 回归要求：旧客户端不带字段时只能落盘；ZIP/文件夹、纯图片、截图不得自动分享；图片加文字的系统分享可进入分享准备。

## DSH-030 Windows SDK 缺少 SQLite 头文件及本地 R2 下载瞬态失败

- 现象：Windows 云构建最初找不到 `winsqlite3.h`；同批远程 HTTP 闭环在 R2 已上传并提交后，立即下载偶发返回一次 HTTP 500。
- 根因：部分 Windows SDK 包含系统 `winsqlite3.lib/dll`，但不安装开发头文件；Wrangler 本地 R2/DO 运行时启动后的首次跨绑定下载存在瞬态失败，正式协议与纯测试不受影响。
- 修复：项目仅声明实际使用的少量系统 SQLite C 接口，继续链接 Windows 自带运行库，不额外捆绑 DLL；HTTP 烟雾测试只对下载阶段的 500 做 5 次、每次 200ms 的有限重试，其他状态不重试。
- 证据：第二备用 run `30269352290` 的 Windows 构建、SQLite 测试和真实 Worker/DO/R2 HTTP 闭环通过。
- 回归要求：不得因缺头文件改为下载来源不明的 SQLite DLL；远程重试必须有次数和时间上限，持续 500 仍需失败并输出 Worker 日志。

## DSH-018 Durable Object 升级后纯测试通过、真实 R2 下载返回 500

- 现象：0.1.1 的语法、类型、10 项纯协议测试和 Wrangler dry-run 都通过，但第二备用 CI run `30265632265` 在真实启动 Worker 后，接收端下载 R2 对象返回 HTTP 500；同一 run 的三端应用构建不受影响。
- 根因：为接入当前 `getByName()` 路由时仍导出旧式普通 Durable Object 类，Node 测试无法直接加载 `cloudflare:workers`，导致运行时入口与可测试核心没有正确分层。
- 修复：`src/index.js` 只保留 Cloudflare 运行时包装类并正式继承 `DurableObject`；协议状态机移入 `src/relay-core.js`，Node 测试直接使用核心类；CI 的 HTTP 失败会自动输出 Worker 日志。
- 证据：本地 10 项协议测试、类型检查与 dry-run 通过；第二备用 CI run `30266289195` 实际启动 Worker、Durable Object 和 R2，完成上传、下载、ACK 删除，远程任务成功；Windows、Android、iPhone 同 run 全部成功。
- 回归要求：不得为了让 Node 直接导入入口而移除 `DurableObject` 基类；每次修改远程运行时必须同时保留纯协议测试和 Linux Worker/DO/R2 HTTP 闭环。

## DSH-015 系统文件管理器删除后仍显示私有“幽灵作品”

- 现象：外部 `Download/Lark` 已删除作品，首页仍可预览、复制并分享；外部回收站为空，App 回收站仍有旧副本。
- 根因：SAF 导入为保证分享稳定会保存 App 私有图片副本，旧逻辑只增量导入、不核对来源是否仍存在；MIUI 文件服务还可能短暂返回空目录或保留失效索引行，使整批扫描中断。
- 修复：每次刷新核对来源文档 ID；单项失效不阻断整批；第一次缺失只记录，至少 2 秒后再次查询仍缺失才移除私有副本；没有外部来源 ID 的网络接收作品不参与删除。
- 证据：红米 K60 外部目录实际 24 个作品、0 个回收项；升级前私有库 26/2，覆盖后日志为 `sourceRemoved=2 trashRemoved=2`，界面恢复 24/0。
- 回归要求：模拟文件服务首次返回空列表时必须保留有效作品；手动删来源后再次刷新必须移除对应私有活动项和回收项。

## DSH-016 重复拖入同一素材无法识别

- 现象：Windows 每次拖入都会新建任务，无法知道同一内容是否已传给同一设备。
- 根因：没有成功传送历史和顶层内容指纹。
- 修复：文件用 SHA-256；文件夹用排序后的相对路径、文件大小和逐文件 SHA-256 生成指纹。只有接收端 commit 或 USB 校验完成后才按设备写入本地历史。
- 回归要求：重命名但内容完全相同的文件夹也应提醒；文件数相同但内容不同的文件夹不得误判；用户明确选择重传时必须允许发送。

## DSH-017 匿名 USB 接口误合并到另一台 Wi‑Fi 安卓手机

- 现象：电脑同时看到一台局域网安卓和另一台只开启充电/调试接口的 USB 安卓时，USB 状态被挂到局域网设备卡片上。
- 根因：匿名 USB 接口没有可用设备名，旧合并逻辑在列表里只有一台局域网安卓时直接猜测两者是同一设备。
- 修复：匿名接口不再按在线数量合并；MTP 开启后，以 USB 厂商和设备硬件标识将同一实体的 WPD 与底层接口合并。
- 证据：K60 仅开放调试接口、红米 9A 同时通过 Wi‑Fi 在线时复现误合并；开启 MTP 后 Windows 又暴露有名底层接口与匿名可写 WPD。最终 K60 实体日志从 `usb_discovery devices=2` 收敛为 `devices=1`，界面只显示一张 `Redmi K60` 卡片。
- 回归要求：多台安卓在线时不得按数量猜身份；无法确认身份时宁可分开显示；同一 Android 开启 MTP 后只能显示一张真实设备卡。

## DSH-001 Android 作品重复一倍

- 现象：实体目录 11 个作品，界面显示 22 个；
- 根因：SAF 与旧系统隐藏目录兼容通道同时发现同一真实目录，历史迁移并发又可能重复导入；
- 修复：按规范化真实来源路径跨通道去重，迁移进程内串行；
- 证据：Redmi Note 8 连续冷启动稳定显示 11；
- 回归：保留“双通道同源”和“并发初始化”自动测试。

## DSH-002 HarmonyOS 隐藏作品漏扫或全盘误扫

- 现象：带点号目录最初被忽略；放开后又把大量非作品目录计入扫描；
- 根因：把“隐藏”直接等同于“无效”，或把递归经过目录数误当作品数；
- 修复：仅忽略明确系统目录和回收站；作品必须由同一目录直接包含图片与 TXT 判定；界面数字只统计作品；
- 证据：实体 Huawei 目录的隐藏与普通作品合计按实际数量显示；
- 回归：覆盖点号作品、系统隐藏目录、空目录和深层聚合目录。

## DSH-003 回收站只改数据库、没有移动真实文件

- 现象：App 里清空后，文件管理器仍看到原作品文件夹；
- 根因：早期实现只删除应用索引，没有操作用户授权目录中的真实文件；
- 修复：在授权根目录建立应用回收站，移动真实作品目录；恢复反向移动；清空删除回收站真实内容；
- 回归：每次操作后同时检查 App 列表和系统文件管理器，失败必须给出明确原因。

## DSH-004 Windows 文件夹 ZIP 出现空文件或同名目录

- 现象：接收后出现空文件和带 `(1)` 的同名目录；
- 根因：Windows ZIP 目录项可能使用反斜杠，接收端只按 `/` 判断目录；
- 修复：目录项同时接受 `/` 与 `\`，仍拒绝绝对路径和 `..`；
- 回归：保留 Windows 反斜杠 ZIP、越界路径和同名冲突用例。

## DSH-005 iPhone 只看到 `.Trash` 或漏掉深层作品

- 现象：选中总目录后只显示垃圾目录，真实作品位于“作品包 → 单个作品”更深层；
- 根因：扫描只看一层，或没有正确使用 security-scoped URL；
- 修复：授权期间有限递归；同目录含图片与 TXT 才是作品；忽略 `.Trash` 与应用回收站；遇到作品后停止继续向下；
- 回归：覆盖两层/三层作品、空包、`.Trash`、权限撤销和 iOS 12 Documents 分支。

## DSH-006 手机互相发现一闪即逝

- 现象：Android 短暂看到 iPhone 后消失；iPhone 看不到 Windows；
- 根因：部分路由器不在无线客户端之间转发 `255.255.255.255`，9 秒离线窗口也过短；
- 修复：同时发送全局广播和按 IPv4/掩码计算的子网定向广播；在线保留时间改为 15 秒；进入传送页主动探测；
- 证据：Android 间隔 20 秒两次采样都同时显示 Windows 与 iPhone，iPhone 也记录到两端；
- 回归：至少等待超过一个 TTL 后再次检查，不能只截“刚进入页面”的一瞬间。

## DSH-007 Windows 把自己显示成可发送设备

- 现象：电脑设备列表出现本机；
- 根因：发现包没有稳定本机 ID 过滤，或多网卡地址造成自广播被当成远端；
- 修复：生成稳定 Windows deviceId，并同时按 ID 过滤自身；
- 回归：有 Wi-Fi、USB 虚拟网卡和 169.254 地址时仍不显示自己。

## DSH-008 Android → iPhone 最终 timeout，iPhone 无反应

- 现象：Android 显示传送超时；iPhone 没有接收通知，也不返回 HTTP；TCP 地址和设备发现正常；
- 根因：`NWListener` 连接回调里创建的 HTTP 请求读取器只被弱引用，回调结束后提前释放，连接存在但永远没人读完请求并响应；
- 修复：读取器在开始时自持有，响应发送完成或连接失败后再释放；iPhone 前台接收时保持唤醒；
- 证据：前台 32 秒后 `/v2/info` 立即响应；任务创建、SHA-256 上传和取消为 201/200/200；Windows 正式文件提交成功；
- 回归：每次改 iOS 网络层都要包含“连接后延迟发送”“完整上传”“取消”和“前台持续 30 秒”检查。

## DSH-009 iPhone 侧载安装失败或看似密码错误

- 现象：安装停在最后、设备列表为空、账号输入后弹出不同错误；
- 根因：USB 信任、Apple 驱动、iTunes/iCloud、代理、账号验证、签名和 IPA 结构属于不同层，不能统一归为密码问题；
- 修复：固定按 USB → iTunes → 侧载工具 → 账号 → 签名安装 → App 功能分层诊断；IPA 内部路径使用 ASCII；
- 回归：CI 解包验证 IPA 的 `.app` 路径、可执行文件、版本和显示名；安装成功不能代替功能测试。

## DSH-010 构建额度与账号切换

- 现象：主账号 Actions 额度不足，或切到备用账号后主仓库 push 报 Repository not found；
- 根因：私有 Actions 额度按仓库所有者计算；Git HTTPS 凭据跟随当前 GitHub CLI 活跃账号；
- 修复：主仓库保持唯一真源，把同一提交推送到备用私有构建仓库运行；操作前显式切账号并配置 Git，完成后切回主账号；
- 回归：比较主源码、备用构建仓库的 commit SHA；发布前确认当前账号和推送目标；凭据只存系统密钥库。

## DSH-011 更新索引已推送但 raw 暂时仍是旧版

- 现象：GitHub API 已显示新提交，`raw.githubusercontent.com` 短时间仍返回旧 `latest.json`；
- 根因：Raw CDN 缓存传播延迟；
- 修复：等待后重新读取应用实际使用的公开 raw 地址，不能只验证 Git 提交或 API；
- 回归：正式发布必须记录 raw 返回的 `version_name`、`version_code` 和 APK SHA-256。

## DSH-012 移除启动通知权限时误删旧机型存储依赖

- 现象：第一轮 0.4.2 云构建中，iPhone 与 Windows 成功，Android 在 Java 编译阶段找不到 `Manifest` 和 `PackageManager`；
- 根因：删除 Android 首次启动的通知权限申请函数时，同时删除了仍被 Android 10 / HarmonyOS 隐藏目录兼容代码使用的两个导入；
- 修复：恢复存储兼容所需导入，只移除通知权限申请调用和方法；声音通知权限改由设置开关按需申请；
- 证据：备用构建 run `29693667340` 的 Android 单元测试、编译、Lint 与 APK 上传全部成功；
- 回归：精简权限代码后必须全局检查同名类型的其他用途，并同时跑旧系统兼容分支的完整编译。

## DSH-013 iPhone 6 固定目录没有可用的文件入口

- 现象：iPhone 6 能启动“相册”，但系统“文件”App 已被删除，固定作品库扫描为 0，设置里只能重新扫描，不能选择外部总文件夹；
- 根因：iOS 12 不具备当前外部文件夹持久授权路径，旧实现又假定系统“文件”App 一定存在；第三方文件管理器仍受沙盒限制，不能从根本上补齐入口；
- 修复：同一客户端在 iOS 12 自动使用固定作品库，并在空作品页、设置页增加文件/ZIP 导入；多选素材自动归组，标准 Deflate ZIP 由 App 自行解压；较新系统继续保留外部文件夹选择；
- 回归：覆盖系统“文件”App 不存在、导入取消、多选图片+TXT、存储式 ZIP、Deflate ZIP、重名目录和越界路径。

## DSH-014 iPhone 6 设置页没有“选择作品文件夹”

- 现象：iPhone 12 可以通过系统选择器设置外部总文件夹，iPhone 6 只显示固定目录和重新扫描，用户无法在已接收的多个文件夹之间切换扫描根目录。
- 根因：iOS 12 没有 iOS 13 的外部文件夹持久授权能力，旧兼容分支因此把“固定 Documents”错误地等同于“不需要选择入口”。
- 修复：同一 IPA 内按系统能力切换；iOS 13+ 保留系统外部文件夹选择器，iOS 12 增加 App 内文件夹选择器，递归列出相册接收目录及其子目录，并持久保存所选相对路径。接收和扫描都落到当前所选根目录。
- 回归：iOS 12 覆盖选择全部接收内容、选择一级/多级子目录、原目录被移除后的安全回退；iOS 13+ 继续覆盖 security-scoped bookmark 恢复。

## DSH-015 点击更新跳转网页

- 现象：Android 和 iPhone 的“检查版本更新”发现新版后打开 GitHub 页面，用户还要再次找安装包，缺少下载进度与校验。
- 根因：更新检查只读取发布页地址，没有消费 `latest.json` 中的直接 APK 地址与 SHA-256，也没有区分 Android 可调用系统安装器和 iPhone 侧载必须重新签名的系统边界。
- 修复：Android 直接在 App 内下载 APK、显示进度、校验 SHA-256，再调用系统安装器；iPhone 不再跳网页，明确提示通过电脑侧载覆盖更新。
- 回归：覆盖无新版、网络失败、取消下载、错误 SHA-256、未知来源权限未开、下载后系统安装器打开，以及 iPhone 不打开浏览器。

## DSH-016 重名回收站里的旧作品重复出现在首页

- 现象：iPhone 6 实体首页显示 4 个作品，其中两项与正常作品同名；App Documents 中同时存在“相册回收站”“相册回收站 (1)”和“相册回收站 (2)”。
- 根因：文件接收遇到同名目录会追加数字避免覆盖；扫描器只排除了精确名称“相册回收站”，仍会递归数字重名副本。
- 修复：Android 与 iPhone 同时识别“相册回收站”“_相册回收站”及其“ (数字)”副本；文件夹选择列表也不再提供这些回收站副本。
- 回归：覆盖原名、旧版下划线名、数字重名副本、非数字括号和名称相似的普通目录，避免扩大排除范围。

## DSH-017 旧 iPhone 大文件夹长时间 Wi-Fi 传送中断

- 现象：iPhone 6 接收约 264 MB 单 ZIP，上传到 50% 时设备退到桌面，前台接收服务停止并重置连接；任务取消后没有业务文件落地。
- 根因：iPhone 接收服务按产品边界只在 App 前台运行；旧设备和较慢 Wi-Fi 让单连接持续数分钟，离开前台就会中断。
- 处理：普通传送继续使用局域网协议；旧 iPhone 连接 USB 且大目录达到 64 MiB 时，共享技能优先使用 App 文件共享，先写隐藏暂存区，核对文件数与总字节，再原子改名。没有 USB 时继续要求相册保持前台并保留自动取消。
- 证据：实体大目录样本在电脑和手机均为 129 个文件、263,885,655 字节，App 识别 14 个作品；共享技能 Wi-Fi 小文件提交返回 received=1，随后清理测试文件。
- 回归：大目录测试必须覆盖前台持续、主动离开 App、失败取消、隐藏暂存清理、同名目标拒绝覆盖和最终数量/字节核对。

## DSH-018 Android 主应用可分享、应用分身卡住

- 现象：从相册分享给小红书主应用正常；在红米 K60、VIVO 的应用分身中，目标可能不出现、进入后卡住或点击无反应；系统图库分享给同一分身正常。
- 根因：旧版图片来自 `com.zwm.gallery.files` 应用私有内容提供器。标准临时 URI 授权在主用户内有效，但部分厂商的分身运行在隔离用户空间，跨空间解析和转发应用私有 URI 不稳定；系统图库使用系统媒体库 URI，厂商已覆盖该通道。
- 处理：Android 10+ 把原图字节复制到系统 MediaStore 的短期分享区，发布完成后分享系统媒体 URI；同时给当前可解析的目标补充显式只读授权。准备失败时保留普通分享兜底并明确提示。
- 安全边界：不伪装包名、不自动点击、不读取分身账号；临时副本下一自然日清理，删除目标严格限制为应用自己记录的 `media` URI。
- 实体证据：红米 K60 同签名覆盖 0.4.7 后，从相册选择 10 张图分享至小米分身小红书；系统实际进入 `u999` 的图片编辑页，10 张缩略图完整加载。随后返回，未执行发布或保存草稿；首页作品数和回收站未改变。
- 回归：主应用与分身分别覆盖单图、多图、返回、再次分享；核对文案复制、图片数、分享次数、次日临时副本清理。VIVO 分身继续沿用同一公共实现，不制作设备专包。

## DSH-019 手动回收与滚动时工具栏消失

- 现象：作品很多时顶部常用按钮滚出屏幕；用户也无法在不打开分享的情况下主动把某个作品移入回收站。
- 处理：首页使用冻结工具栏结构，只有作品内容区滚动；长按作品进入批量选择状态，所有卡片显示复选框，可勾选多个，右下角红色垃圾桶统一移动真实来源文件夹和应用私有副本，保留分享次数。
- 失败保护：真实文件夹尚未移动时会回滚应用状态；系统已经完成移动时绝不把应用记录贸然退回原列表。
- 回归：长按、复选框多选/反选、返回键取消、批量部分失败、确认/取消、文件夹权限失效、恢复、清空和 7 天边界都要覆盖。
- 实体证据：红米 K60 的 0.4.8 连续勾选两项后显示“已选 2 个”，所有卡片均显示复选框；按返回取消后首页仍为 11、回收站仍为 2，没有移动用户文件。

## DSH-020 iOS 营销版本已更新但内部 build 没更新

- 现象：首次 0.4.7 云产物的 `CFBundleShortVersionString` 为 0.4.7，但 `CFBundleVersion` 仍为 17。
- 根因：XcodeGen 配置同时存在 `CURRENT_PROJECT_VERSION` 和显式 `Info.plist` 属性，后者仍硬编码旧值并覆盖构建设置。
- 修复：显式属性同步为 18，IPA 结构检查同时断言营销版本和 build，避免只核对用户可见版本。
- 回归：每次 iOS 升级都解包 IPA 核对两个字段及最低 iOS 版本。

## DSH-021 iPhone 覆盖安装停在 Installing 0%

- 现象：Sideloadly 已完成 Apple ID 会话、设备注册与签名，进入 `Installing...` 后长期保持 0%；目标 iPhone 上的旧相册仍在前台提供 `/v2/info`。
- 根因：本次实体表现为目标 App 占前台时安装服务没有继续替换，而不是密码、签名、USB 信任或安装包错误。
- 处理：先核对 Sideloadly 选中的最终 bundle id 与设备现有 App 完全一致；保持设备解锁，把旧 App 退回桌面。无人工触控条件时，可用已配对的 `pymobiledevice3` 挂载开发镜像并只发送一次系统 Home 键。不得卸载 App 或清除容器来绕过停滞。
- 实体证据：iPhone 12 的旧 App 退出前台后，Sideloadly 立即从 0% 完成到 100%；设备应用清单为 0.4.7 build 18，启动后仍扫描出原有 23 个作品。
- 回归：每次覆盖前记录旧版本、最终 bundle id 和作品数；安装后再次核对三项。若 bundle id 被 Sideloadly改写，必须确认与该设备既有最终标识一致再继续。

## DSH-022 旧 Android 在 Wi-Fi 重连后互相发现失效

- 现象：Redmi 9A / Android 11 上，手机与电脑曾经互相不可见；但 App 进程和 HTTP 接收端口仍在，电脑直接读取设备状态正常。重开 App 后会暂时恢复。
- 根因：UDP 发现循环只有最外层一次异常捕获。Wi-Fi 切换期间向发现请求方回复时出现临时 `ENETUNREACH`，异常会让整个发现线程永久退出，而 HTTP 线程不受影响。
- 修复：把一次 UDP socket 生命周期变成可恢复会话；运行中发生网络异常时等待短暂退避并重新绑定端口，正常停止服务时不重试。诊断分别记录重试和恢复。
- 自动回归：模拟首个发现会话抛出临时网络错误，断言同一服务进入第二个会话；模拟服务已停止，断言关闭 socket 不产生重试。
- 实体证据：同一正式升级链覆盖 Redmi 9A 后，原目录授权、作品数和接收设置保留；应用进程不重启，Wi-Fi 断开再恢复后，电脑重新发现手机，手机同时看到 Windows 与 iPhone；超过设备过期时间再次检查仍在线。

## DSH-023 VIVO 分身冷启动提前返回导致误报失败

- 现象：选择 VIVO 应用分身后，系统选择器很快退回相册；分身小红书仍在后台缓慢加载，旧逻辑会显示超时，用户稍后手动进入却能看到图片。
- 根因：OriginOS 的分身路由先返回调用方，再冷启动隔离用户中的目标进程；“系统选择器返回”既不等于失败，也不等于目标已经打开。
- 修复：系统媒体写入完成并可读取后再分享；记录用户选择的目标包，目标过早返回时继续观察前台应用，只有目标真实打开才增加分享次数，否则提示重试。
- 实体证据：VIVO 0.5.0 诊断先记录 `share_target_returned_early`，随后记录 `outcome=deferred_target_opened`；测试后删除精确测试作品的临时分享计数字段，其他数据未改动。
- 回归：主应用、分身热启动、分身冷启动分别测试；不得仅用 `onActivityResult` 判定成功，也不得用固定等待直接伪造成功。

## DSH-024 作品图片预览比例失真及旧 iPhone 内存压力

- 现象：近方形缩略图不能直观看出 3:4 作品构图；直接解码所有原图在 iPhone 6 多图作品中可能造成明显卡顿或内存压力。
- 修复：Android 与 iPhone 统一两列 3:4 预览；点图时再加载较大预览。iPhone 使用 ImageIO 按目标像素降采样，列表不保留完整原图位图。作品直接目录中的 TXT、ZIP、JSON、PDF 等非图片附件继续显示类型、文件名和大小，点按使用系统预览能力。
- 交互：长按进入多选，底部仅显示垃圾桶、三点连线分享、小飞机三个图标；图片回收站按作品和日期保存 7 天并可恢复。
- 回归：单图也必须保持半列宽，不能拉伸占满整行；多图滚动、复用、选择边框、恢复和第 7 天清理都要覆盖。

## DSH-025 Android 正式包误用 Debug 构建及全面屏顶部过近

- 现象：0.5.0 公布的 APK 清单包含 `debuggable=true`，部分厂商安全引擎可能结合安装包权限和联网行为给出通用灰色风险提示；水滴屏/Android 15 边到边系统的冻结工具栏也可能靠状态栏过近，42dp 图标不够容易点击。
- 根因：工作流一直上传 `assembleDebug` 产物；界面只使用固定顶部间距，没有消费新系统的状态栏、导航栏和显示挖孔安全区。
- 修复：0.5.1 改为同一证书签名的 `assembleRelease`，显式关闭调试；所有 Android 页面在 Android 15+ 消费系统栏与挖孔 Insets，首页按钮扩大为 48dp 并调整窄屏间距。
- 兼容边界：不能直接更换签名证书，否则旧版无法覆盖升级并可能导致用户状态丢失；应用内直接更新仍需 Android 的安装包请求权限，系统最终确认不会被绕过。
- 本机证据：单元测试、Release 编译和 Release Lint 成功；APK 为 0.5.1/code 28，v2 签名验证通过，证书摘要与旧版一致，合并清单未出现 `debuggable=true`。
- 回归：同签名覆盖安装后核对目录授权、作品数、分享次数、回收站和更新入口；在水滴屏实体检查四个顶部按钮点击；再次观察厂商安全提示，不把本机检查替代真机结果。

## DSH-026 Release 包关闭 Debug 后厂商风险提示仍存在

- 现象：0.5.2 已确认是非 Debug 的 Release APK，但出现过风险提示的 Android 手机仍给出同类通用安全提示。
- 证据：0.5.2 清单没有 `debuggable=true`，但仍包含 `android.permission.REQUEST_INSTALL_PACKAGES`；该权限只服务于旧版 App 内下载后直接调用安装器。
- 单一假设：厂商安全引擎把“联网 + 请求安装 APK”作为风险信号之一。仅关闭 Debug 不足以消除提示；不能在同一轮同时换签名，否则既破坏覆盖升级，也无法判断究竟哪个变量生效。
- 处理：0.5.3 删除安装包请求权限和直接安装流程，改由 Android DownloadManager 下载；用户点系统完成通知安装。后台保留 SHA-256 核对，不一致时删除下载。
- 自动证据：单元测试、Release 编译和 Release Lint成功；本机构建为 0.5.3/code 30，v2 签名证书与旧版一致，APK 权限清单不含 `REQUEST_INSTALL_PACKAGES`，也没有 Debug 标志。CI 同时断言这两个条件。
- 回归：在此前出现提示的实体手机覆盖安装 0.5.3，核对应用数据、系统下载、完成通知、安装确认、哈希失败提示和厂商安全扫描结果。没有实体结果前，不把权限清单变化等同于风险提示已经消失。

## DSH-027 华为分享已打开但次数没有增加

- 现象：HarmonyOS / EMUI 设备把作品图片交给目标应用后，返回相册仍没有“已打开分享 N 次”，次日整理也缺少这次本机记录。
- 根因：部分系统选择器会先回调 `onActivityResult`，稍后才发送 `Intent.EXTRA_CHOSEN_COMPONENT`。旧逻辑在目标回调到达前立即按取消结束并注销接收器，真实目标选择因此漏记。
- 修复：把目标回调和 Activity 结果交给同一状态判定器；结果先到时等待 1.2 秒，晚到的目标回调仍记为打开分享，超时且确实没有回调才按取消。正常 Android 与 VIVO 分身的快速返回等待路径保持不变。
- 自动证据：4 项时序测试分别覆盖晚到回调、回调缺失、快速目标返回和长时间目标返回；Android 全量 37 项单元测试、Release 编译和 Release Lint 成功。
- 实体状态：Huawei P30 局域网上报 Android 10、相册 0.5.2、27 个作品；本轮电脑没有 USB/ADB 枚举，0.5.4 覆盖安装与真实分享操作需要设备重新以 USB 调试连接后复核。
- 回归：华为普通分享、系统选择器取消、VIVO 分身热/冷启动、小米主应用与分身都要检查；取消分享不能加次数，晚到回调不能重复加次数。

## DSH-028 已回收作品以另一扫描副本重现并造成重复分发

- 现象：Redmi Note 8 上已经分享、甚至手动移入回收站的作品仍以未分享状态出现在首页，用户再次进入同一小红书账号后形成重复内容。
- 实机证据：旧版 0.5.0 的当前列表有 16 项且全部没有分享字段；回收站 19 项中有正常次数记录。内容哈希确认其中 2 个当前项与回收站项的图片集合完全一致，另有 2 个当前作品内部存在字节完全相同的重复图片。
- 根因一：旧逻辑只有目标应用回调成功才调用 `markShared`，厂商选择器、取消或冷启动回调不稳定时，用户已经点过入口但作品仍保持未分享。
- 根因二：Android 10 同一隐藏目录可通过 SAF 和旧存储兼容通道得到不同内部编号。旧元数据缺少稳定的跨通道来源关联，回收一份后另一份仍能留在当前列表。
- 修复：0.5.5 在 ShareActivity 首次创建时立即原子记账，后续系统回调只做诊断；再次分享先确认。作品库增加“文案 + 去重后的图片 SHA-256 集合”内容指纹，只在当前项与回收站项完全一致时保留回收状态，并去除应用私有副本内的重复图片。
- 数据边界：不按名称猜测，不改用户原图片和文案；覆盖升级前先备份应用私有数据。0.5.5 当时暂不处理旧日期；0.5.6 起改用 DSH-029 的北京时间迁移与同日 1 小时宽限，避免历史回收站长期堆积。
- 实体证据：Redmi Note 8 同签名覆盖后版本为 0.5.5/code 32，名称与 Lark 授权保留；当前作品从 16 收敛为 14，回收站保持 19，应用私有作品内重复哈希组从 2 组降为 0。临时自检作品点击一次后 `shareCount=1`，模拟满 1 小时后作品、MediaStore 跟踪项和临时目录均被清理，最终恢复 14/19。
- 回归：取消系统分享面板仍只记一次；旋转/恢复 Activity 不得二次记账；跨通道同内容但不同编号只保留回收状态；相同图片但文案不同不能误合并；旧记录按 DSH-029 的跨日到期与同日宽限处理。

## DSH-029 文件模式像新页面、旧回收站长期不清与 ZIP 图标误导

- 现象一：Android 点文件夹图标后进入独立 `FileBrowserActivity`，标题、状态区和返回逻辑全部变化，用户感知为跳进另一套应用；五个顶部入口同时挤压标题和数量。
- 现象二：旧 VIVO 与 iPhone 的昨天作品和回收站仍按自然日/7 天模型保留；仅把新分享改成小时级，会让历史堆积永远没有精确锚点。
- 现象三：早期 ZIP 图标轮廓接近垃圾桶，文件模式只有图片/文件夹较容易辨认。
- 修复：Android 把文件浏览直接嵌入 `MainActivity`，同一冻结顶栏只替换下方内容；返回先退目录、再切回作品。标题、数量、圆形按钮、图标和间距重新收紧，并给五个纯图标入口增加短文字反馈。文件类型改用系统文件管理器式图标，ZIP 为文档加拉链。
- 迁移：Android 与 iPhone 按北京时间把旧日期迁移为精确毫秒。昨天及更早锚定到旧日零点并按当前规则处理；当天锚定升级时刻，完整保留 1 小时；iPhone 无状态旧回收站从升级时刻保留 1 小时。迁移写回状态且幂等。
- 实体证据：VIVO Y36t 从 0.5.4/code 31 同签名覆盖后，Lark 授权、设备名和 13 个当前作品保留；旧回收站 15→0，真实 `Download/Lark/相册回收站` 为空；同日 1 次分享作品获得升级时刻锚点，没有立即删除。0.5.6 Release 覆盖后仍为 13 个作品，`run-as` 明确拒绝并报告非 Debug。
- 界面证据：VIVO 实机确认五个入口、标题与数量同排完整显示；模式切换前后顶部坐标和 Activity 均不变，文件根目录显示 15 项，ZIP 显示拉链文件图标；返回键原地回到 13 个作品。
- 回归：320/360dp、大字体、水滴屏、模式往返、两级目录返回、全部文件类型、同日/跨日旧状态、孤立回收站、迁移幂等、覆盖安装数据保持。iPhone 清理结果必须在新 IPA 覆盖后单独记录，不能用 Android 实体结果代替。

## DSH-030 iOS 新版本编译成功但旧打包断言拦截

- 现象：0.6.6 源码已通过 Xcode 真机 SDK 编译，工作流仍在“Package AltStore IPA”失败并未上传 IPA。
- 根因：工作流把 IPA 名称、营销版本和 build 固定写成 0.6.5/build 24；工程升级到 0.6.6/build 25 后，旧断言把正常新包判为失败。
- 修复：同步更新 IPA 名称、Info.plist 版本断言、校验文件和上传资产名为 0.6.6/build 25。
- 回归：每次提升 iOS 版本时同时检索工作流中的旧版本字符串；最终 IPA 必须解包实读 `CFBundleShortVersionString` 和 `CFBundleVersion`，不能只以 Xcode 编译成功收口。

## DSH-031 Android 断点接收提交被孤立回收目录阻塞

- 现象：作品 ZIP 已完整上传，提交阶段却返回 `trash/<id>/meta.properties: open failed (ENOENT)`，导致自动补发重试后仍无法落库。
- 根因：回收站清理或断点导入在目录元数据检查后移动了 `meta.properties`；作品库遍历直接读取已不存在的文件，把可跳过的孤立目录当成致命错误。
- 修复：遍历作品目录时对元数据文件做二次存在性确认；仅忽略确认已经成为孤立目录的 `FileNotFoundException`，真实元数据读取失败继续抛出。不会删除目录或用户作品。
- 回归：覆盖孤立回收目录重启作品库、正常作品保留、断点文件名复用和大小/SHA-256 不匹配拒绝；安装新 APK 后再用同一任务验收提交与自动补发。
