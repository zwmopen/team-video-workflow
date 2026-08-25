# 变更记录

## Android 0.6.58 - 自动接收开关（统一正式入口）

- 设置页新增“自动接收”开关，默认开启，升级不会改变已有接收习惯。
- 关闭后同时拒绝局域网 HTTP、P2P 和远程中继的新内容；正在接收的临时任务会停止并清理，不会写入半成品。
- 设备仍保持在线发现、版本上报和本机查看能力；重新打开后恢复接收。
- `/v2/info` 增加 `autoReceiveEnabled` 状态字段，便于电脑端识别手机当前是否允许投送。
- versionCode 96 / versionName 0.6.58；Android、iPhone 和 Windows 统一读取正式更新入口。

## 修复候选：Android 0.6.57 - Android 10 Keystore 不阻断局域网在线

- 修复 Redmi Note 8 / Android 10 实测的 `Unknown purpose: 64`：旧款 OEM Keystore 不支持远程中继 ECDH 密钥用途时，不能再阻断 `deviceInfo()`。
- 局域网 UDP 发现和本地 HTTP 接收现在会正常上报；仅将可选 Cloudflare 中继标记为不可用，等后续设备支持时再启用，不影响同 Wi‑Fi 传输。
- versionCode 95 / versionName 0.6.57；已在目标 Redmi Note 8（Android 10）覆盖安装并实测闭环：工作台 UDP 发现成功、HTTP 45833 `/v2/info` 成功、状态 `online`、原作品库存 `2` 正常上报；该机仅 `relayEnabled=false`，不影响局域网传输。

## 修复候选：Android 0.6.56 - Note 8 局域网发现与接收服务自恢复

- Android 接收端持有 Wi-Fi 多播锁，兼容部分旧款小米系统对局域网发现包的省电过滤；停止服务时释放锁，不改变作品数据和传输协议。
- 发现广播在系统未返回接口广播地址时按 IPv4 网段补算广播地址，并保留 `255.255.255.255` 兜底。
- HTTP 接收线程和 UDP 发现线程分别记录运行状态；任一线程瞬时失败后，下一次前台刷新可以只恢复缺失线程，不再因总服务仍标记运行而永久离线。
- versionCode 94 / versionName 0.6.56；本地源码修复候选，尚未发布 Beta，需构建与 Redmi Note 8 真机回归后再发布。

## Beta：Android 0.6.55 - 断点接收提交容错

- 修复自动补发 ZIP 已上传完成、提交阶段却因 `trash/<id>/meta.properties` 残留目录返回 500 的问题；作品库现在会安全跳过确认已孤立的回收目录，不删除目录、不影响正常作品。
- 断点发送端在任务大小与 SHA-256 一致时复用接收端原文件名，避免重试生成新时间戳文件名导致“断点任务的文件名不匹配”。
- Android versionCode 93；由 GitHub Actions run `32803292676` 构建通过，真实红米 13 仍需安装 0.6.55 后用同一断点任务验收落库与自动补发。

## 0.6.54 / iPhone 0.6.41 - 2026-08-24（已发布 Beta）

- Android 接收端在最终 commit 失败后清理临时任务并恢复在线，避免卡在“接收中”导致电脑端永不重试。
- iPhone 预览页增加确认删除按钮，修复预览计数；长按图片进入多选时避免普通点击抢占。
- Device Share Hub run `32688671241` 的三端构建、中继 E2E 和发布校验全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.54-beta.1>。
- 稳定版索引保持 Android 0.6.29 / iPhone 0.6.16；真实设备安装和自动补货仍需手机现场验收。

## Beta：Windows V4.3.29 / Android 0.6.53 / iPhone 0.6.40 - USB 失败自动降级

- USB 传送失败后会先清理临时目录，再自动继续走同一 Wi‑Fi、P2P 直传和 HTTPS 中继；用户不需要手动重发。用户主动取消时仍立即停止，不会偷偷改走另一条通道。
- 保留 USB 的事务性暂存与回滚，避免 USB 部分写入后产生重复作品；新增 USB 失败降级回归门禁。
- 三端版本提升为 Windows V4.3.29、Android 0.6.53/versionCode 91、iPhone 0.6.40/build 59；本轮需要由 GitHub Actions 云端构建并更新 Beta 索引。
- 真实 Android/iPhone 的 USB、同 Wi‑Fi、跨网 P2P、HTTPS 回退、作品库 ACK 和精准库存低于 5 自动补货仍必须现场验收。

## Beta：Windows V4.3.28 / Android 0.6.52 / iPhone 0.6.39 - 远程更新包与混合传输闭环

- 安卓 APK 更新包现在明确标记为 android-update；Cloudflare 中继、Windows 原生 P2P 和安卓接收端统一传递并校验该类型，只有 APK 完成签名/版本校验并写入更新缓存后才发送 ACK。
- Beta Windows 不再读取稳定版 latest.json，自动移动端更新改读 latest-beta.json；APK 不再走只复制文件的 USB 分支，避免误报“更新已送达”。
- iOS 明确拒绝安卓更新包，不会把 APK 当成普通作品导入；普通作品仍保持 USB、同 Wi-Fi、P2P 优先、HTTPS 中继兜底。
- 线上 Worker 已部署版本 `4feb3749-a8fd-4a20-a218-6cf5628b51fc`；真实线上 E2E 已覆盖健康检查、P2P 信令、普通作品 R2 闭环，以及 `android-update` P2P/收件箱/ACK 清理。
- Windows 直连 Wi-Fi 发生端口、连接或提交错误时，现在会清理未完成的局域网任务并自动尝试 P2P，再回退到 HTTPS 中继；不再因为“发现了 Wi-Fi”就放弃远程兜底。
- 本轮三端由 GitHub Actions 云端构建并发布 Beta；实体 Android/iPhone 的安装、P2P、HTTPS 回退和自动补货仍需现场验收。

## Beta：Windows V4.3.27 / Android 0.6.51 / iPhone 0.6.38 - 原生 P2P 数据面验收

- Windows 原生 libdatachannel 新增双 PeerConnection loopback：实际发送 256 KiB+137 字节二进制分片，接收端校验帧顺序和完整字节后回 ACK；云端 CTest 已通过并报告 100% tests passed。
- 修复 Windows P2P 发送端和接收端 DataChannel 生命周期：清理前阻断 SDP/ICE/状态回调，释放 PeerConnection；避免发送返回后回调触碰已释放状态。
- 本轮 Device Share Hub run `32601626368` 的 Android、iOS、Windows、remote-relay check 和线上 Worker E2E 全部通过；PR 分支验证完成，合并 main 后才会触发 Beta 发布索引。
- 真实 Android/iPhone 的 USB、同 Wi-Fi、跨网 P2P、HTTPS 回退、作品库落库/ACK、精准低于 5 自动补货仍需手机在线现场验收，不能用云端 loopback 代替真机证据。

## Beta：Windows V4.3.26 / Android 0.6.50 / iPhone 0.6.37 - HTTPS 代理与远程设备列表容错（已发布）

- Windows 远程中继新增可选 `ZWM_DEVICE_SHARE_RELAY_PROXY` 环境变量和 `%LOCALAPPDATA%\ZwmDeviceShareHub\relay-proxy.txt` 配置；只通过 HTTPS CONNECT 代理访问 Cloudflare，不降级到明文 HTTP。修复远程模块 User-Agent 仍写 V4.3.22 的版本不一致。
- 修复 Worker `/v1/devices` 遇到历史损坏成员记录时整页返回 500；现在记录告警并跳过损坏项，其他有效设备仍能继续在线和自动分发。
- 三端版本同步为 Android 0.6.50/versionCode 88、iPhone 0.6.37/build 56、Windows V4.3.26；Device Share Hub run `32596690436` 的三端构建、remote-relay check 和线上 Worker E2E 全部通过。Worker 最新部署版本为 `fb0ba8fb-6f15-46f3-b31d-00cbeba36c59`。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.50-beta.1>；Android SHA-256：`f007889e30526059438df53d816e5a6d8f348b01371e1da74958a96fe3f19b64`；iPhone IPA SHA-256：`6cfaa0f7e7db5bf544cdbf172f663d06e9f6c0b06b1ffe59dae9568615dfe2e0`；Windows SHA-256：`b7319e0b72d049ea641fca5f944401b6c7fbdc2b8a104efb84128dfccd8c646`。
- Beta `latest-beta.json` 与 AltStore Beta 源已同步并复核；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。三端包已同步到 `C:\Users\z\Desktop`，桌面中控当前运行 V4.3.26、TCP 45833/UDP 45834。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待现场设备验收。

## Beta：Windows V4.3.25 / Android 0.6.49 / iPhone 0.6.36 - P2P 信令会话清理修复（已发布）

- 修复 Android/iPhone P2P 传输完成、失败或取消后只关闭 WebRTC PeerConnection、未释放 Cloudflare 信令会话的问题；现在会关闭信令会话，避免下一轮在线轮询重复接收旧会话并重新协商。
- 三端版本同步为 Android 0.6.49/versionCode 87、iPhone 0.6.36/build 55、Windows V4.3.25；Device Share Hub run `32594831524` 的 Android、iOS、Windows、remote-relay check 和线上 Worker E2E 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.49-beta.1>；Android SHA-256：`06fdbfa2ce1a55eccd00b958fe1723fbab4c798c74d39b3d0c7cd30959e0b515`；iPhone IPA SHA-256：`4687f0fa1163c428e06610ce10fc8edcb367530274288f6c9467b7f76e24edca`；Windows SHA-256：`50c3693612ccdf256d52f04b23bf9ae71a5aa3207b402e083128199a8e3724f0`。
- Beta `latest-beta.json` 与 AltStore Beta 源已同步并以 Raw URL 复核；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。三端包已同步到 `C:\Users\z\Desktop`，桌面中控当前运行 V4.3.25、TCP 45833/UDP 45834。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待现场设备验收。

## Beta：Windows V4.3.24 / Android 0.6.48 / iPhone 0.6.35 - iOS P2P ICE 与版本元数据修复（已发布）

- 修复 iOS P2P 信令轮询与 WebRTC 回调并发读写 ICE 候选队列的竞态；新增 P2P ICE、ACK 延迟刷新、会话回收、HTTPS 回退和库写入顺序静态门禁。
- 统一 iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` 与 `Info.plist`：0.6.35/build 54；Android 为 0.6.48/versionCode 86；Windows 为 V4.3.24。
- Device Share Hub run `32593184579` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.48-beta.1>。
- Android SHA-256：`501f1bed085a1b37b13deb89a2bc5879b07a01c1ec42c6108a59bb49ce53d15d`；iPhone IPA SHA-256：`2ad5da8a18d7b62565812d6d9c81cdb164f204605354a4686c8db2244e06921b`；Windows SHA-256：`7f622d23ce8c66dfd80df4445549bbc758bae155ef99309d4d9d65bb71cd1776`。
- Beta `latest-beta.json` 和 AltStore Beta 源已同步；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待连接验收。

## Beta：Windows V4.3.23 / Android 0.6.47 / iPhone 0.6.34 - 可选测试版更新通道（已发布）

- Android 和 iPhone 设置新增“更新通道”：默认稳定版，切换到测试版后分别读取 `latest-beta.json` 和 `altstore-beta.json`，不会把 Beta 包推给稳定用户。
- Android 测试版更新会继续使用 HTTPS 下载、SHA-256 校验和系统安装确认；iPhone 会复制当前通道对应的 AltStore 源，仍由 AltStore/AltServer 完成签名更新。
- Device Share Hub run `32590760825` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.47-beta.1>。
- Android SHA-256：`ad3e248c6d988b9c832c4ed5a7b3272b7e13f62d7ad33769b37f76faffc9e465`；iPhone IPA SHA-256：`6e2e34a9ec46c4e013a6beeaefd2913d9c685c5674c6f06e47e12c0601e11a88`；Windows SHA-256：`0dcc312aadd7b883a79b0436e6a98f5dbe4ffeb8c14e5b21f14b0ff2b5d7d4cb`。
- Beta `latest-beta.json` 和 AltStore Beta 源已同步；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待连接验收。

## Beta：Windows V4.3.23 / Android 0.6.46 / iPhone 0.6.33 - 混合通道状态合并修复（已发布）

- 修复同一台手机同时被局域网探测和 Cloudflare 中继发现时，局域网记录会覆盖远程在线状态、凭证和库存的问题。
- 为 Wi‑Fi 路由保存独立的最近观察时间；远程心跳不再把旧 IP 伪装成当前 Wi‑Fi 路由，远程设备会正确进入 P2P 优先、HTTPS 中继兜底路径。
- 发送前只把 35 秒内真实观察到的 Wi‑Fi 当作直连；旧地址失效时不再卡在错误 Wi‑Fi 路径。新增源码回归门禁，并同步更新 Windows 网络 User-Agent。
- Device Share Hub run `32589239907` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.46-beta.3>。
- Windows SHA-256：`3a560cb55968173324b80c545ab7315e79f8625e3e1227dc339972ba70ff7b51`；Android SHA-256：`4278644080854a55380f4ad5c160935270fd7d04d316f925cccb58168745b955`；iPhone IPA SHA-256：`3d011d2331b70b6a3f023f1045814a41253202c71c0df36cac24012bd9efc5ba`。
- 三端包已同步到 `C:\Users\z\Desktop`；稳定 `latest.json` 和 AltStore Beta 源不切换，真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待连接验收。

## Beta：Windows V4.3.22 / Android 0.6.46 / iPhone 0.6.33 - 远程库存合并边界修复（已发布）

- 修复同一台手机同时被局域网和远程中继发现时，缺失的远程库存字段覆盖本地完整库存的问题；远程字段只有在实际携带值时才合并，精准自动补货不再因心跳缺字段偶发漏发。
- Device Share Hub run `32587368303` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.46-beta.2>。
- Windows SHA-256：`8888a99131fb7174a5d5730f5cf2a9cbcec1c9d570b9d920d76dbe9a58ac5da1`；Android/iPhone 沿用 Beta1 已核对的 SHA-256；稳定 `latest.json` 和 AltStore Beta 源不变，实体手机验收仍待连接。

## Beta：Windows V4.3.21 / Android 0.6.46 / iPhone 0.6.33 - 远程库存心跳与自动补货闭环（已发布）

- 修复远程中继只上报在线状态、不上报手机精准库存的问题；Android/iPhone 每 10 秒心跳同步总数、精准/泛/未分类库存和版本信息，Durable Object 校验后提供给 Windows。
- Windows 远程设备现在合并库存并统一进入 USB/Wi-Fi/远程三路可用判断；右键“发送到”、自动更新候选和精准流量低于阈值的自动补货都不再漏掉无局域网 IP 的远程手机在线设备。
- 旧手机没有分类库存字段时继续按“未知”处理，不会拿总作品数冒充精准流量；Device Share Hub run `32586026767` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.46-beta.1>；Android SHA-256：`30a0d3ca3376e00f1f6c3a0563e71804a7a80bd6aaddad34d2290fc8b27f902c`；iPhone IPA SHA-256：`d1953c1e8dc5b0ff6851d62c8eecbc3bba663b51341e01bffac8ace70b9e4f6f`；Windows SHA-256：`4429a9a7f9f295fa68989ab1d76b191638c651e01f2ac496f5aca79243524977`。
- 三端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源内容提交为 `4578bce603ea5476252f25bc8a74c2ef719e30b5`，稳定 `latest.json` 仍保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35；实体手机验收仍待连接。

## Beta：Windows V4.3.20 / Android 0.6.45 / iPhone 0.6.32 - 自动补货递归发现修复（已发布）

- 修复自动补货只扫描作品库第一层的问题；现在会递归发现实际生产目录下的 `作品集_xxx[转]` / `作品集_xxx【转】` 精准作品文件夹，仍不会拿泛流量目录替代精准库存。
- 新增源级回归门禁，要求自动补货使用递归扫描并保留精准标签判断；Device Share Hub run `32583765953` 的 Windows、Android、iOS、remote-relay check 和 live E2E 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.3>；Android SHA-256：`e19f189b276a239059a5d884f5dd12129d61ef174cc848bd489922ed0ca05784`；iPhone IPA SHA-256：`302e9bd46d9dfd5fe558adec096891224681a97315465ed4cae167addc89bb04`；Windows SHA-256：`69305cec244bb37f928bba13cf86868cceb120a56fa191bf4693ff752ba2ef50`。
- 三端包已同步到 `C:\Users\z\Desktop`；稳定 `latest.json` 和 AltStore Beta 的 iOS 语义版本不变，真实手机验收仍待连接。

## Beta：Windows V4.3.19 / Android 0.6.45 / iPhone 0.6.32 - 自动分发默认配置修复（已发布）

- 新建或升级 Windows 内容数据库时，自动手机更新、精准流量自动补货和阈值 5 会自动写入默认值；用户明确关闭或修改阈值后不会被覆盖。
- 当前电脑数据库已同步为 `auto_mobile_update_enabled=1`、`auto_restock_enabled=1`、`auto_restock_threshold=5`，原数据库备份保存在临时目录；手机上线后会直接进入自动分发验收。
- Device Share Hub run `32581609531` 的 Windows、Android、iOS、remote-relay check 和 live E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.2>。
- Android SHA-256：`4e91dff5c7e926e8ba668c2322542d45584055345c6471c76ae09deb1ca06d2b`；iPhone IPA SHA-256：`19e1ab04cc83ac0f0df573c03c52dd6167a267d776af3408013f0b75cd01a52d`；Windows SHA-256：`bc570acacda62b490a3f73503d72fa3808e7e64d7111f19f20b7a00a2118c2f9`。
- 三端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已指向 Beta 0.6.45 的 iOS 包，源提交 `83de151d181976cb72e7389790acfaa12cb2eec5`。稳定 `latest.json` 和 Android/iOS 稳定版本不变；实体手机回归仍待连接。

## Beta：Windows V4.3.18 / Android 0.6.45 / iPhone 0.6.32 - 2026-08-22

- 修复 Windows 手机自动更新在传输开始前就写入“已发送”状态、失败后永久不重试的问题；现在只在传输成功后写入已送达记录，失败会记录可重试状态。
- Android/iPhone 在线信标新增向后兼容的精准、泛、未分类库存尾字段；Windows 可在 /v2/info 暂时不可用时继续按精准流量判断自动补货。
- 新增自动更新重试和库存信标源级回归门禁；Device Share Hub run `32579462284` 的 Android、iOS、Windows、remote-relay check 和 live E2E 均通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.1>；Android SHA-256：`220ea5a469e37ee051840ecbe541705f9267888a3d8c55c6e14aaecc90102aa0`；iPhone IPA SHA-256：`21106f8ca543bfcb940dd15fa7fada8735e3a075a5364b5026acf9833ee569f8`；Windows SHA-256：`d2d02e0e1cf313cf8e42efd3aa4cd58a8a2af437e8f56eee39b8606081b74e35`。
- 三端云端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已更新为 iOS 0.6.32/build 51，源提交 `a0591f1401365e4ae252882083ea9eda51082f8e`。稳定 `latest.json` 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35；实体手机回归仍待连接。

## Beta：Windows V4.3.17 / Android 0.6.44 / iPhone 0.6.31 - 2026-08-22

- 修复 P2P 已写入作品库但 ACK 丢失后，Windows 回退 HTTPS 中继造成 Android 重复入库的问题；已导入的 transfer 现在只补发 ACK，不会再次下载或创建作品。
- Android 成功 ACK 后清除远程收件占位并清理重试缓存；iOS 重复 P2P 完成路径释放活动引擎。
- 版本递增为 Windows 4.3.17、Android versionCode 82 / versionName 0.6.44、iPhone 0.6.31/build 50；Device Share Hub run `32575362864`、Repository quality `32575362946`、Secret scan `32575362919` 和线上 Worker E2E job `97036844830` 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.44-beta.1>；Android SHA-256：`0fe499cf1040d12c28ba9ef8c5aa8e7f2d214475d6de7c88e985f27d4430381d`；iPhone IPA SHA-256：`1db6768df098beb2c8924d5b7fb0b923d81ff53678b840bc297787fe1cd25c92`；Windows SHA-256：`f89106a50bd4d73295a098e803382648680c3fbd90d642d70499a0c736a4af52`。
- 三端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已更新为 iOS 0.6.31/build 50，源提交 `f46cca7fd98504ea4c195e89a75a6697a80d197a`。稳定 `latest.json` 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。

## Beta：Windows V4.3.16 / Android 0.6.43 / iPhone 0.6.30 - 2026-08-22

- 修复 Windows 远程中继任务在上传/提交失败后没有立即取消的问题；失败时清理 R2 临时对象和收件箱任务，避免孤立任务等待 TTL。
- 三端版本递增为 Windows 4.3.16、Android versionCode 81 / versionName 0.6.43、iPhone 0.6.30/build 49；Device Share Hub run 32571763937、Repository quality run 32571763918、Secret scan run 32571763916 全部通过，正式 Worker E2E job 97028121778 通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.43-beta.1>；Android SHA-256：15131AC4163FDD0111B0BC9497868D50E1B82E8E8F5352BA1845501B4828FBF9；iPhone IPA SHA-256：5E8DE6EF4FA435D19B361B889B791B9B8DED80989AF4E6A7CA08DD15587214E5；Windows SHA-256：AE8893BFA3F2B2072CCCB44413848E9DFB341985702014B7A818320AFA912F11。
- 三端新云构建包已同步到 C:\Users\z\Desktop；AltStore Beta 源已更新为 iOS 0.6.30/build 49，源提交 6131dcf4faea6f39c0b49261b471f05079a1c3d6。稳定 latest.json 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。

## Beta：Windows V4.3.15 / Android 0.6.42 / iPhone 0.6.29 - 2026-08-22

- 修复 Android P2P ICE 候选入队与远端 Description 回调同时发生时的竞态；候选入队和排空现在由同一把锁保护，避免候选丢失后不必要地回退中继。
- 三端版本同步提升为 Windows 4.3.15、Android versionCode 80 / versionName 0.6.42、iPhone 0.6.29/build 48；GitHub Actions Device Share Hub run 32565416952、Repository quality run 32565417059、Secret scan run 32565416950 已通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.42-beta.1>，AltStore Beta 源已更新；Android SHA-256：B53D470F0D30115CA493BF162F9E196F6277962B9E4EF00E712B6FA6DCBC6955；iPhone IPA SHA-256：716730F02C949338BB923FD28485F053D4856897D4795088EAE35496AE362017；Windows SHA-256：DD9E256A4A6B41C2084719E2B82CF5FB09858351827DD2BB3798DAD3D1A8784C。
- 三端云构建包已同步到 C:\Users\z\Desktop；稳定 latest.json 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。真实 Android/iPhone/Windows 设备验收仍待连接，未使用本地 Android Gradle、Xcode 或 Windows 构建替代证据。
- 工程门禁新增正式 Cloudflare Worker 线上 E2E；最新 run 32568228480 的 `remote-relay-live-e2e` job 97019921822 已验证中继控制面、P2P 信令、R2 临时对象和 ACK 删除，仍不替代实体手机跨网实传验收。
- 校正 `docs/REMOTE_PROTOCOL_V1.md` 的过期状态，明确当前是公开作品 `plain` 链路、三端 P2P 数据面已接入、Cloudflare 已部署，且真机跨网验收仍是未完成项。
- 新增云端隐私回归门禁：Android/iOS 构建前检查截图监听、悬浮窗、后台读剪切板和相册读取入口，APK 产物再检查已删除的高风险权限，防止旧功能回归。
- 修复 Windows 远程中继在任务已创建后上传/提交失败时未立即取消任务的问题；现在会清理 R2 临时对象、收件箱任务和本次失败的 transferId，避免孤立任务等待 TTL。

## Beta：Windows V4.3.14 / Android 0.6.41 / iPhone 0.6.28 - 2026-08-22

- 修复 Android P2P 引擎跨 WebRTC 回调、信令轮询、文件队列和超时线程读取状态时的可见性问题；peer、channel、finished 和远端描述状态现在使用 volatile，避免活连接被误判为超时或结束后继续清理。
- GitHub Actions Device Share Hub run 32562005856 已通过；Repository quality run 32562005857、Secret scan run 32562005864 已通过，remote-relay 测试 13/13 通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.41-beta.1>，已包含 Android 0.6.41、iPhone 0.6.28 和 Windows 4.3.14 三端资产；AltStore Beta 源内容提交为 e58a7b76cb0ecb96b7de05e519f89acf6fc27b41。
- Android SHA-256：e9bd39eb83b1bdc516c4824fdb4a451cdad97dc31d741d16fc0a9f8de9989cb5；iPhone IPA SHA-256：b03f6fa4ba9830b88f059fc1a5fe41f07d4b24d8e852ec6049c7e77e4a28deed；Windows SHA-256：a65a325f248e397cba5eacc8057ebffe03227c8cdb3296720353d9d9c11b5bd0。
- 三端云构建包已同步到 C:\Users\z\Desktop；稳定 latest.json 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35，没有把 Beta 推入稳定通道。
- 真实 Android/iPhone/Windows 设备当前仍未连接；云构建、协议测试和发布资产不能替代真机安全扫描、P2P 成功与 HTTPS 中继回退验收。

## Beta：Windows V4.3.13 / Android 0.6.40 / iPhone 0.6.27 - 2026-08-22

- 清除 Android Manifest 中遗留的 `READ_MEDIA_IMAGES` 与 `READ_MEDIA_VISUAL_USER_SELECTED`；手机端没有自动截图采集、截图观察器或悬浮窗功能，不再为这条旧链路声明相册读取权限。
- 保留 Android 10 隐藏作品兼容通道所需的旧存储权限声明与 SAF 文件夹授权；它只在用户主动选择作品文件夹后用于兼容导入，不读取系统剪切板或自动扫描截图。
- Windows、Android、iPhone 版本同步提升为 `4.3.13`、`versionCode=78` / `versionName=0.6.40`、`0.6.27/build 46`；GitHub Actions Device Share Hub run `32559885341`、Secret scan `32559885315`、Repository quality `32559885563` 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.40-beta.1>；Android SHA-256：`f14d708707c8d1ed5be3ae81e0873f644ec5d30d2496592439a6898cba2d6faa`；iPhone IPA SHA-256：`98d671107d6ad686870fcebdd1f64a0198960e14276cd532ac2b6baeabbbd61f`；Windows SHA-256：`994a20ecb0ef01f533bb99e71dfacf1d73886c019127e986ef5a54d48cbcff23`。
- 桌面包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已更新为 iOS `0.6.27/build 46`，内容提交 `d64b3df036109a52fa62b1fc997cb938419d9b6e`。稳定 `latest.json` 仍保持 Android `0.6.29` / versionCode `67`、iPhone `0.6.16` / build `35`。
- 真实 Android/iPhone/Windows 设备仍需连接后复测安装、权限列表、安全扫描、作品收发、P2P 和 HTTPS 中继回退；云构建和发布不替代真机业务验收。

## Beta：Windows V4.3.12 / Android 0.6.39 / iPhone 0.6.26 - 2026-08-22

- 修复 Android P2P 引擎在 PeerConnection 创建瞬间失败后仍被放进活动 map 的问题；现在启动即失败会被丢弃，下一轮收件轮询可以正常重试，不会卡成“已处理中”。
- Android 取消已结束的 P2P 引擎时安全忽略重复清理，不再因为已关闭的执行队列抛出异常；iOS 原有的启动失败保护保持一致。
- 版本号已提升为 Windows `4.3.12`、Android `versionCode=77` / `versionName=0.6.39`、iPhone `0.6.26/build 45`。
- GitHub Actions run `32558264352` 已完成 Android、iOS、Windows 和 remote-relay 检查；安全扫描 run `32558264343`、质量检查 run `32558264394` 通过。Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.39-beta.1>。
- Android SHA-256：`ff6153918f9c8ac7793e9ce7a0c1284ed20e63243a94c8780d106a41e0bd3f23`；iPhone IPA SHA-256：`189cd17b4904dd97b8fa750559fcb6eeabb018a3274c1367967c2534339b8506`；Windows SHA-256：`ea87a27b79198e091a09e927c0a3369bc919bfd3846ac38210bd284a4d28bb13`。
- 桌面包已同步到 `C:\Users\z\Desktop`；Beta iOS 更新源已更新为 0.6.26/build 45。稳定 `latest.json` 保持 Android `0.6.29` / versionCode `67`、iPhone `0.6.16` / build `35`，没有把 Beta 推入稳定通道。
- 真实 Android/iPhone/Windows 设备互传验收仍待连接设备，不把云构建、协议测试或 Release 发布当作真机业务通过。

## Beta：Windows V4.3.11 / Android 0.6.38 / iPhone 0.6.25 - 2026-08-22

- 修复 Android/iOS P2P 收件端在发送端没有建立 DataChannel 时可能长期等待的问题；现在建连超过 20 秒会主动失败并进入 HTTPS 中继回退。
- Android 不再在 WebRTC 回调线程执行文件写入和 SHA-256 校验，先复制数据帧，再交给串行传输队列处理，降低大文件传送卡死和回调阻塞风险。
- 版本号已提升为 Windows `4.3.11`、Android `versionCode=76` / `versionName=0.6.38`、iPhone `0.6.25/build 44`。
- GitHub Actions run `32556181728` 已完成 Android、iOS、Windows 和 remote-relay 检查；安全扫描 run `32556181727`、质量检查 run `32556181732` 通过。Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.38-beta.1>。
- Android SHA-256：`9558e76b1fac6ae5bea98219a86645f594d783e6e324f3a07cc141fe09f7a368`；iPhone IPA SHA-256：`14920197a1811a144be995be9eb8f082245a0cd1d54df0a7f0799aa76b5f12e2`；Windows SHA-256：`22353fd7631fe32730205e00646ae5f91546789a749e0471c4e0489ec0c2876e`。
- 桌面包已同步到 `C:\Users\z\Desktop`；Beta iOS 更新源已更新为 0.6.25/build 44。稳定 `latest.json` 保持 Android `0.6.29` / versionCode `67`、iPhone `0.6.16` / build `35`，没有把 Beta 推入稳定通道。
- 真实 Android/iPhone/Windows 设备互传验收仍待连接设备，不把云构建、协议测试或 Release 发布当作真机业务通过。

## Beta：Windows V4.3.10 / Android 0.6.37 / iPhone 0.6.24 - 2026-08-22

- 修复 Android/iOS P2P 接收端失败后只清本地引擎、没有立即关闭 Cloudflare 信令会话的问题；现在失败会异步关闭控制面会话，不阻塞 WebRTC 失败回调，避免等 2 分钟 TTL 才回收并让 HTTPS 回退更干净。
- 版本号已提升为 Windows `4.3.10`、Android `versionCode=75` / `versionName=0.6.37`、iPhone `0.6.24/build 43`。
- GitHub Actions run `32553627094` 已完成 Android、iOS、Windows、remote-relay、质量检查和安全检查；三端安装包已上传到 Beta 发布页 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.37-beta.1>。
- Android SHA-256：`f13132b8311f333b07aba44d8527e644a16c474838f696f81d5a30721e2dc011`；iPhone IPA SHA-256：`44d768c2f401ba25e926bbec4b769094d4695a9732359fcfc986fd806bcdeb`；Windows SHA-256：`9eeefaf815fc841bfcf616029ece0c654e6278ba94a974690515a46186d71bf2`。
- 桌面包已同步到 `C:\Users\z\Desktop`；真实 Android/iPhone/Windows 设备互传验收仍待连接设备，不把云构建当作真机业务通过。

## Beta：Windows V4.3.9 / Android 0.6.36 / iPhone 0.6.23 - 2026-08-22

- 修复 P2P 成功 ACK 刚写入发送队列就关闭 DataChannel 的尾部竞态；移动端等待短暂刷新窗口后再关闭，避免电脑误判失败并重复走 HTTPS 中继。
- iPhone 成功导入后清理 P2P 临时缓存；Windows DataChannel 背压连续停滞 20 秒时主动失败，让既有 HTTPS 中继回退真正生效，不再无限卡住。
- 修复 Windows 在 P2P 会话已创建后发生文件校验、信令或传输异常时未关闭控制面会话的问题；现在所有异常路径都会先清理会话，再进入 HTTPS 中继回退，避免留下脏会话。
- Android `versionCode=74` / `versionName=0.6.36`；iPhone `0.6.23/build 42`；Windows `4.3.9`。同一次 GitHub Actions 三端云构建已通过，实体设备业务验收仍待连接手机。

## Beta：Windows V4.3.8 / Android 0.6.35 / iPhone 0.6.22 - 2026-08-22

- 把之前只有 SDP/ICE 信令的“P2P”补成真实 DataChannel 数据面：Windows 使用 libdatachannel，Android 使用 WebRTC SDK，iPhone 使用 WebRTC XCFramework。
- 传输顺序固定为 manifest → 48 KiB 二进制分片 → 完整性校验 → 作品库导入 → ACK；任何建连、断线或校验失败都会自动回退到现有 Cloudflare HTTPS 中继。
- P2P 信令仍只经过 Cloudflare，公开作品字节不进 Worker/R2；本阶段不增加应用层加密、截图、剪切板、悬浮窗或无障碍权限。
- Android `versionCode=73` / `versionName=0.6.35`；iPhone `0.6.22/build 41`；Windows `4.3.8`。必须以同一次 GitHub Actions 三端构建和实体手机业务验收作为交付边界。

## Beta：Windows V4.3.7 / Android 0.6.34 / iPhone 0.6.21 - 2026-08-21

- 补齐混合传输实际链路：Windows 首次发现手机时读取手机公钥，自动签发成员凭证并通过现有局域网下发；手机保存后每 10 秒登录 Cloudflare 中继并上报在线状态。
- Windows 原生面板新增远程中继发送兜底和远程设备在线轮询；USB/Wi-Fi 不可用时，自动创建普通公开任务、上传 R2、提交，手机写入作品库后 ACK 清理。
- 修复 Worker 路由要求工作区身份但移动端后续请求未带 `X-Workspace-Id` 的断链问题；Android/iOS 的心跳、收件箱、任务查询、对象下载和 ACK 全部补齐工作区头。
- 新增真实部署烟测 `remote-relay/scripts/cloudflare-e2e.mjs`，已实测健康检查、设备会话、在线心跳、R2 上传、提交、下载 SHA-256、ACK 与 R2 删除。
- Windows 原生面板 `4.3.7`；Android `versionName=0.6.34` / `versionCode=72`；iPhone `0.6.21` / build `40`。

## Beta：Android 0.6.33 / iPhone 0.6.20 - 2026-08-21

- 远程中继新增 `mode: plain` 普通公开作品传送：电脑上传普通 ZIP，手机按对象字节数与 SHA-256 校验后写入现有作品库，成功后才发送 ACK；失败不会 ACK，云端临时对象会继续保留等待重试或过期清理。
- Android 与 iPhone 都已接入远程对象流式下载、临时文件校验、作品库导入和 ACK；重复轮询与 ACK 失败恢复不会重复导入已确认的任务。
- 新增桌面发送脚本 `remote-relay/scripts/send-public-work.mjs`，支持作品文件夹自动打包或直接发送 ZIP。文件内容不做应用层加密；链路仍要求 HTTPS，认证凭证不写入仓库。
- Android `versionName=0.6.33` / `versionCode=71`；iPhone `0.6.20` / build `39`。
- 本地中继协议测试已覆盖“创建 → 上传 → 提交 → 收件箱 → 下载对象 → ACK 删除”。Cloudflare Worker、Durable Object 和 R2 已部署到 `zwm-device-share-relay.zwmrpg.workers.dev`；真实手机安装与异地网络实传仍需单独验收。
- Android 长按多选改为只更新已渲染列表，不再触发完整扫描；iOS 图片多选只刷新受影响的图片格，避免长按选择出现卡顿。

## Cloudflare 中继正式部署 - 2026-08-21

- Worker `zwm-device-share-relay` 已部署，Durable Object 使用 `WorkspaceRelay`，R2 暂存桶为 `zwm-device-share-relay`。
- Wrangler 实读部署版本已更新为 `b4fc48b0-9b3b-4e73-b828-d51abe59ba4c`，部署占比 100%；本机直连烟测受 DNS/连接异常影响，真实手机安装与异地网络实传仍需单独验收。
- 当前账号没有活动 Zone，暂使用 `workers.dev` 公网地址；本机网络对该域名的 DNS/连接异常只影响本机烟测，不改变 Cloudflare 部署状态。
- 混合传输当前是“局域网/USB 直传优先 + HTTPS 中继兜底”；WebRTC/QUIC 打洞仍是后续优化项，中继兜底已经接入 Windows 原生面板。

## Beta：Android 0.6.31 / iPhone 0.6.18 - 2026-08-21

- 在上一版远程身份、会话和心跳基础上，Android/iOS 开始轮询远程收件箱。
- 客户端只接受接收设备匹配、状态为 `ready`、对象序号不重复、密文大小与 SHA-256 格式有效的任务；过期、上传中、目标不匹配或字段损坏的任务会被忽略。
- 同一远程任务在客户端进程内去重，避免每 10 秒心跳重复触发；现有 USB/LAN V2 不变。
- 这一版仍未接入普通文件下载、作品库导入和 ACK，也未部署正式 Cloudflare 服务；后续已由 0.6.33 接续实现普通公开作品链路。
- Android `versionName=0.6.31` / `versionCode=69`；iPhone `0.6.18` / build `37`。

## Beta：Android 0.6.30 / iPhone 0.6.17 - 2026-08-21

- 这是高级远程传送第一阶段的可安装 Beta 测试包，不替换正式稳定版 `main`。
- 继续保留现有 USB、局域网 Wi-Fi、作品列表、精准流量/泛流量口径和隐私收口；本次增加远程中继控制面、设备身份、会话心跳和可续传任务基础。
- Android `versionName=0.6.30` / `versionCode=68`；iPhone `0.6.17` / build `36`。
- Beta 仍未完成真实跨网络收发验收，安装后重点测试启动、作品收发、更新检查和设备在线状态。

## 高级远程传送第一阶段（开发中）- 2026-08-21

- 远程中继任务状态增加对象级上传进度、已上传密文字节数和下一待传对象索引，客户端重启后可继续原任务。
- 同一任务对象重复上传时，服务端校验现有密文大小与哈希元数据后返回 `reused=true`，避免断线重试重复写入。
- Android 与 iOS 接收服务启动时各自在系统密钥存储生成远程签名/密钥协商用 P-256 密钥；私钥不进入日志、发现广播或网络请求。
- Android 与 iOS 新增 HTTPS 中继控制面客户端：用设备签名密钥完成挑战登录，使用短期 Bearer 会话发送在线心跳并读取收件箱/任务状态；收件箱为空和非法地址均有明确边界。
- Android 与 iOS 新增远程登记资料存储：只保存 HTTPS 地址、公开成员凭证和管理员签名；私钥继续留在 Keystore/Keychain，Bearer 会话令牌不落盘。
- Android 前台接收服务与 iOS 局域网接收服务已接入独立远程心跳调度器：未登记时不联网，登记后每 10 秒重新认证/发送心跳，失败只丢弃内存会话并等待下一轮重试。
- 修复远程地址边界：拒绝带路径、查询参数、片段或用户信息的地址，避免接口路径拼接错误；远程登录/心跳失败加入最长 5 分钟退避，并在凭证轮换时立即重新登录。
- 本阶段尚未发布手机包；三端设备凭证、在线会话和真正跨网络收发仍在后续开发与实机验收中。

## Android 0.6.29 / iPhone 0.6.16 更新闭环优化 - 2026-08-20

- Android 从后台回到前台且超过 6 小时未检查时，会静默检查并准备更新，不重复打扰用户。
- iPhone 检查到新版本时，可直接打开 AltStore，或复制更新源；不再把 AltStore 用户引导回旧的“连接电脑”提示。
- Android `0.6.29` / versionCode `67`；iPhone `0.6.16` / build `35`。

## Android 0.6.28 / iPhone 0.6.15 AltStore 自动更新源 - 2026-08-20

- iPhone AltStore 更新源已接入云端发布流水线：首次添加 `https://raw.githubusercontent.com/zwmopen/gallery-updates/main/altstore.json` 后，后续 IPA 发布会自动进入 AltStore 更新列表。Android `0.6.28` / versionCode `66`；iPhone `0.6.15` / build `34`。

## Android 0.6.27 / iPhone 0.6.14 分类名称统一 - 2026-08-20

- 手机端顶部分类按钮统一显示“精准流量”和“泛流量”；电脑端自动补货继续使用同一精准流量口径。
- 仅调整用户界面文案，内部 `conversion` 字段、`[转]`/`【转】` 目录识别和自动补货规则保持不变。
- Android `versionCode=65` / `versionName=0.6.27`；iPhone `0.6.14/build 33`。发布状态以云端构建、Release 和 `latest.json` 为准。

## Android 0.6.26 / iPhone 0.6.13 平台按钮即时状态 - 2026-08-20

- 点击“发抖音”或“发小红书”后，对应按钮立即变为灰色；灰色只表示该平台已经点击过，按钮仍可再次点击。
- Android 从分享页返回作品列表时重新读取已保存的分享次数；iOS 按抖音/小红书各自的点击次数恢复按钮状态，分享准备失败时回滚即时状态。
- 修正云端发布步骤，同时上传 Android APK 与 iOS IPA，并同步 `latest.json` 的两端版本，确保苹果端也能检测到本次更新。
- Android `versionCode=64` / `versionName=0.6.26`；iPhone `0.6.13/build 32`。云端 Actions、Release、`latest.json` 与真实手机安装仍分别核验。

## Android 0.6.25 / iPhone 0.6.12 按钮层级优化 - 2026-08-19

- 作品卡片内的“预览 / 发抖音 / 发小红书”改为纵向紧凑布局，按钮按内容自适应宽度，不再横向等分铺满。
- “预览”改为轻量描边按钮，两个平台入口保留绿色主按钮与分享次数无障碍说明；预览和分享行为不变。
- Android `versionCode=63` / `versionName=0.6.25`；iPhone `0.6.12/build 31`。发布状态以云端 Actions、Release、`latest.json` 和桌面包哈希为准。

## Android 0.6.24 / iPhone 0.6.11 隐私与作品预览收口 - 2026-08-18

- 两端删除自动截图采集、截图中转和自动读取/同步系统剪切板；iOS 删除 `ClipboardBridge`、接收接口和设置开关，Android 继续保留的复制动作都只在用户点击后发生。
- 作品卡片改为一行三个紧凑入口：“预览”“发抖音”“发小红书”，整行仍保持可读性和无障碍说明；平台计数保留在作品信息中。
- Android 作品预览增加“上一张/下一张”；iPhone 作品预览改为左右分页滑动，并显示当前张数。
- Android `versionCode=62` / `versionName=0.6.24`；iPhone `0.6.11/build 30`。本节记录的是云端交付候选，发布状态以 Actions、Release 和 `latest.json` 的一致性核验为准。

## Android 0.6.23 移除剪切板、截图与悬浮窗模块 - 2026-08-18

- 移除手机端共享剪切板、悬浮剪切板、截图监听、截图自动发送和截图中转整套功能；删除对应界面、后台观察器、接收器、HTTP `/v2/clipboard` 接口及中继任务路径。
- 删除 `SYSTEM_ALERT_WINDOW` 悬浮窗权限；普通文件/图片传输仍保留所需的媒体访问权限。用户主动点击“复制并分享”或“复制诊断信息”时的明确复制动作不受影响。
- 该版本移除了会触发系统隐私/风险提示的自动剪切板读取、系统剪切板写回、悬浮窗和截图监测行为；是否仍被厂商安全扫描提示需在真实设备安装后复测。
- Android `versionCode=61` / `versionName=0.6.23`；当前为本地构建候选，未自动发布或安装。

## iPhone 0.6.9 分类库存统一 - 2026-08-18

- iPhone `/v2/info` 新增 `workCounts`，上报精准流量、泛流量和未分类数量，与 Android/Windows 使用同一字段口径。
- Windows 自动补货改为只看精准流量（`conversion`）库存；字段缺失时不自动补发，不再用总数替代。
- iOS GitHub Actions 的 IPA 名称、Artifact 名称和包内版本校验改为从 `project.yml` 动态读取，不再被旧版 0.6.8/build 27 断言卡住。

## Android 0.6.22 手动下载入口 - 2026-08-17

- “设置 → 软件说明”增加可点击的 `gallery-updates` 发布页地址，方便网络受限或不想使用应用内更新时手动下载 APK。
- 点击只打开系统浏览器，不自动安装、不绕过 Android 安全确认；没有可用浏览器时给出明确提示。
- 发布页地址与应用内更新检查共用同一地址常量，避免维护时出现两个不同下载入口。

## Android 0.6.21 大文件断点续传候选 - 2026-08-14

- 修复电脑发送大文件中途断线后必然从头传的问题：电脑为同一内容复用稳定任务 ID，手机保留 `.receiving` 临时文件和任务清单。
- 新增 `GET /v2/tasks/{taskId}` 状态查询；重试前读取手机已收字节，通过 `X-File-Offset` 只发送剩余部分。
- 手机对已有字节和本次续传内容合并计算 SHA-256，完整校验通过后才提交；明确取消任务才会清理断点。
- 未升级的旧手机没有状态接口时仍兼容从头传输；不会把旧协议误判成已支持断点。
- 已通过 Android 单元测试、Python 传输技能单元测试和语法检查；当前 VIVO 仍需安装 0.6.21 后执行真实中断/续传验收。

## 文件传输第一批稳定性修复 - 2026-08-14

- `device-folder-transfer` 的设备发现不再逐个记录局域网超时；改为记录扫描数量、成功数、失败数，并将 Python 日志按大小滚动，避免单次无设备扫描把日志撑到几十 MB。
- Android 接收端会每分钟清理超过 5 分钟没有活动且尚未上传完整的中断任务，删除临时文件并恢复为可接收状态；已收齐文件的提交处理不会被维护任务打断。
- Windows USB 传送先使用临时名称写入，全部完成后再改成正式名称；USB 已开始写入但未完成时停止，不再盲目切换 Wi-Fi，避免半份文件和重复传输。
- 补充 USB 多项目提交回滚：改名阶段中途取消或失败时，已改成正式名称的项目也会按对象 ID 清理，避免留下半批正式文件；普通 Python 发送在 UDP 已找到设备时也跳过无意义的整段局域网扫描。
- Python LAN 发送增加“设备 + 文件名 + SHA-256”成功指纹账本和并发占用；同一文件第二次发送会在创建手机任务前拒绝，避免手机产生第二份接收目录。
- 本轮已通过 Python 传输技能单测（27 项）和 Python 语法检查；缓存的 Gradle 已启动，但因缺少 Android SDK 未进入安卓测试；Windows 原生编译与真机传输验收仍需补验。

## USB 一键复制脚本 - 2026-08-14

- 新增 `scripts/copy-usb-apk.ps1`：自动寻找电脑端最新 APK、筛选已授权 USB Android 手机，并复制到手机的 `Download` 文件夹。
- 复制前读取手机上的同名文件 SHA-256；相同就跳过。复制采用临时文件、传输后校验、最后改名，避免留下不完整文件。
- 桌面提供 `相册_USB一键复制.cmd` 双击入口；脚本只复制文件，不调用 `adb install`，不修改手机应用版本和数据；多台手机时要求明确选择，避免误传。

## Windows V4.3.6 / Android 0.6.20 双通道更新候选 - 2026-08-14

- 手机启动和刷新作品库后，通过在线信标与 `/v2/info` 上报版本、版本码、总作品数、分类库存和更新能力。
- Windows 设备卡显示手机版本及精准/泛库存；版本信息缺失时不自动推送更新包。
- Windows 发送带版本号的 APK 后自动保存本地缓存；手机上线且版本较低时，自动复用现有 LAN V2 更新包投送链。
- 源码推送后由 GitHub Actions 云端构建并生成版本化 Android APK、SHA-256 和 `latest.json`；Windows 不再定时轮询公开索引。
- 仅在发现低版本手机在线且本地没有候选包时，Windows 按需取回已发布包作为 LAN V2 备用通道，电脑和手机使用同一版本。
- GitHub 仍是手机的首选更新通道，电脑局域网推送是 GitHub 不可用时的备用通道；手机端校验和用户确认安装边界不变。
- 本轮仍是源码候选，未宣称 Android 0.6.20 已发布或已完成 VIVO 真机升级验收。

## Android 0.6.19 immediate inventory beacon candidate - 2026-08-13

- After the phone refreshes its local library, the receiver requests one immediate status beacon; the regular beacon and Windows polling remain as fallbacks.
- Category-inventory-unknown protection, approval, threshold, retry, and de-duplication rules are unchanged.
- Source candidate only; not a public release. Complete Android tests, Release/Lint, and real-device verification before publishing.

## Android 0.6.18 旧版 Android 启动兼容候选 - 2026-08-13

- 修复 Android 10 在主界面恢复时拒绝普通后台 `startService`，导致 0.6.17 安装成功后立即崩溃、被误判为 APK 解析/安装失败的问题。
- 浮层刷新在 Android O+ 改走 `startForegroundService`，并将系统拒绝记录到诊断日志；不改包名、签名、作品数据或传输协议。
- 真实 Redmi Note 8（Android 10 / API 29）已覆盖安装并启动验证通过；0.6.18 尚未发布到公开更新通道。

## Windows V4.3.4 更新检测兜底 - 2026-08-08

- 实机手动检查遇到 GitHub 公共 API 限流后，自动改用公开 Release 最新页跳转读取版本标签，不再直接报错结束。
- 保持 V4.3.3 的独立设置中心、数据键和主界面收纳方式不变。

## Windows V4.3.3 独立设置中心 / iPhone 0.6.8 中文导航 - 2026-08-08

- Windows“设置”改为与主应用风格一致的独立小窗口，不再从右上角弹出临时菜单。
- 原始目录、归档目录、自动检测更新、手动检查更新、发送更新包、自动补货与阈值、开机自启、暗色模式、诊断日志和软件介绍统一收纳到设置中心。
- Windows 主界面删除重复的低频配置入口，只保留日常文件浏览、归档、发送和设备操作。
- iPhone 设置、回收站等二级页面的返回按钮统一为中文“返回”，版本升级到 0.6.8/build 27，应用标识和数据目录不变。

## Windows V4.3.2 通用设置 - 2026-08-07

- Windows 主窗口右上角新增“设置”入口，集中管理原始目录、GitHub 更新检查、补货与更新包设置及软件介绍。
- “素材库 / 发送根目录”统一提升为“文件库 / 原始目录（收发文件根目录）”；继续使用原有 `library_path` 键，旧配置无需迁移。
- 原始目录说明明确为通用文件收发目录，可发送和接收任意文件或文件夹，不再把产品用途限制为素材。
- 新增 GitHub Release 版本检查：支持手动检查和默认开启的 6 小时自动检查；发现新版本只打开公开发布页，由用户确认下载和替换。

## Android 0.6.16 / Windows V4.3.1 功能设置 - 2026-08-07

- Android 更新改回应用内 HTTPS 断点下载：下载完成后执行 SHA-256、APK 包名、版本号、版本码和签名校验，再由用户点击通知进入系统安装器，避免 MIUI/部分系统的 DownloadManager 失败。
- Android 接收端识别电脑投送的 APK 更新包：同样执行包名、版本码和签名校验，合格后进入应用私有更新目录并提示安装；普通素材接收路径不变。
- Windows 新增“功能设置”：可开启通用自动补货、设置低库存阈值（默认 7 个作品），按作品文件夹（帖子文本 + 图片）自动投送到低于阈值的在线设备。
- Windows 新增更新包投送入口，可把 APK/EXE/ZIP 发送到选中设备；Android APK 会进入校验安装流程，电脑端继续沿用现有 USB → Wi-Fi、进度、校验和完成反馈。
- Android 单元测试、Release 构建和 Release Lint 已通过；已用一台真实 Android 11 旧版手机完成 0.6.16 APK 的 LAN/V2 投送并收到 commit。Windows 真机/安装包仍需在有 Visual Studio 工具链后验收。

## Android 0.6.15 / iPhone 0.6.7 / Windows V4.2.1 - 2026-08-02

- 修复 Windows 将“发送到”下的子文件夹视为普通复制目的地、导致用户文件被误复制进系统 `SendTo` 目录的问题。
- 右键入口改为根目录单一快捷方式“发送到相册设备”；点击后由中控弹出在线设备选择菜单，再直接读取原文件或文件夹发送。
- 不建立长期备份；现有 ZIP/网络缓冲仍按传送任务结束清理。旧版自有子目录只清理标记文件和快捷方式，遇到未知内容绝不自动删除。
- 增加无预选设备的命令行解析、IPC 序列化回归测试；保留指定设备参数的向后兼容。
- 设备选择改在单实例 IPC 返回后异步弹出，避免用户选择期间第二进程 5 秒超时并误报失败。
- 右键入口改为纯发送：不再查询或拦截“是否传送过”，选择设备后立即显示主窗口进度条和状态，结束时弹出成功/失败结果。
- 右键发送增加不抢焦点的短文字提示：开始、传送状态和完成/失败结果会在屏幕右下角短暂显示，不保留常驻进度窗。
- 应用内选择设备后直接传送也使用同一组短文字提示，避免只有右键入口有反馈。
- 修复右键入口强制拉起主界面的问题：未运行时静默启动后台发现/接收进程，运行时保持原窗口状态，只显示设备选择菜单和短提示。
- “发送到相册设备”快捷方式不再随普通退出删除，右键可直接按需唤起隐藏后台实例；更新或卸载时由安装流程清理。
- 明确 Windows 自带“发送到 → 桌面”仍是本机复制；发送到手机/电脑设备应选择“发送到相册设备”。
- Android 接收端增加静默系统进度通知和完成结果通知；“声音提醒”只控制声音，不再把通知整体关闭。
- “设置素材目录”改为“设置发送根目录”，该目录同时作为电脑端发送浏览和接收落盘位置；左侧列表支持逐级展开文件夹并选择任意层级文件或文件夹发送。

## Android 0.6.14 / iPhone 0.6.7 / Windows V4.2.0 - 2026-08-02

- Windows 运行期间动态维护资源管理器“发送到 → 相册在线设备”子菜单，只显示当前可用的 USB/Wi‑Fi 设备。
- 增加默认局域网 `/24` 的原生并发 V2 探测兜底，解决路由器抑制 Wi‑Fi 广播回复时共享技能能找到手机、电脑版却显示空列表的问题。
- 右键选中的一个或多个文件、文件夹通过单实例 IPC 交给已打开的中控，直接复用现有传送进度、取消、通道选择、重复提醒和完成回执。
- 设备离线后自动移除菜单项；中控正常退出时清理自身菜单目录，不触碰用户的其他“发送到”快捷方式。
- 快捷方式参数使用编码后的设备 ID，文件路径仍由 Windows 原样传入；主进程会再次校验目标、路径、数量和在线状态。
- 新增参数编码、IPC 序列化和反序列化自动测试；右键入口不需要管理员权限或资源管理器扩展 DLL。

## Android 0.6.14 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- 按用户确认恢复 Android 系统下载更新：打开应用自动检查，发现新版先提醒，点击“系统下载”后交由 DownloadManager 显示进度与完成通知。
- 用户从系统下载通知进入安装；相册不再声明 `REQUEST_INSTALL_PACKAGES`，也不再申请“允许来自此来源”。
- 删除应用自有 APK 下载服务、私有安装包、应用内安装 Activity 和主动拉起安装器的路径；后台文件传送保持不变。
- 系统下载完成后仍按任务 ID 与 SHA-256 后台核对，错误文件自动移除并在下次进入相册时提示。
- 更新包保持纯英文 `.apk` 文件名；本机 20 个测试套件共 66 项测试、Release 构建、Lint、清单、版本和 v2 签名检查通过。
- Android 传送页增加“自动 / USB / Wi‑Fi”选择并记忆。USB 模式提供系统 USB 网络共享入口；经 `rndis/usb/ncm/tether` 网卡发现的电脑显示为 `USB`。
- 手机→电脑的 USB 通道复用 V2 传送协议；普通 MTP 仍用于电脑→手机，不伪装成手机可主动访问的双向协议。

## Android 0.6.13 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- 更新流程改为确认式：打开应用自动检查，发现新版先弹窗，用户点击后才开始下载。
- 下载完成并校验后不再强行启动可能被系统拦截的安装页，改为应用内“安装”按钮与常驻通知双入口。
- 安装提醒明确显示版本、纯英文 `.apk` 文件名和文件大小；点击“安装”后才交给 Android 系统安装器。
- 后台文件传送逻辑不受本次更新交互调整影响。
- 更新包改由相册自己的 HTTPS 下载服务获取，绕开 MIUI/迅雷下载内核访问 GitHub 时返回状态 700 的故障；下载进度常驻通知，网络中断保留 `.part` 并可续传。
- 下载仅接受 HTTPS 及有限 HTTPS 跳转；完成后严格核对 SHA-256、APK 解析、包名、版本号、版本码和签名，再保存为纯英文 `.apk`。
- 更新检查改读 GitHub Release API 和同一 Release 的校验清单，解决中国移动网络访问 `raw.githubusercontent.com` 连续超时。
- APK 签名校验同时读取 Android 新旧签名字段，兼容 Redmi/Android 10 能解析 v2 APK、但新版归档签名字段为空的厂商实现。
- 0.6.11 作为修复后的实体升级起点；0.6.12 真机下载已到校验阶段，最终版提升为 0.6.13。

## Android 0.6.8 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- Android 文件夹模式入口移到首页左侧，解决窄屏顶部操作挤在右侧的问题。
- 修复分类白色滑块覆盖文字，并校正内边距与四等分宽度；切换动画保留。
- 下拉刷新增加拉动、释放、加载中旋转进度和完成数量反馈。
- 设置开关改为应用独立绘制的完整 iOS 胶囊，统一绿色、灰色、白色滑块和过渡动画，不再受厂商 Switch 样式影响。
- 截图自动发送增加主设备选择行与在线状态；未选择目标时开启自动发送会立即弹出选择。
- APK 下载并验证通过后，应用在前台会自动打开系统安装页；后台场景继续使用完成通知作为系统兼容回退。
- Redmi Note 8 同签名覆盖安装、分类、下拉刷新、设置开关和主设备选择完成实体检查；Android 65 项单元测试、Release 构建与 Release Lint 通过。

## Android 0.6.7 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- 双端首页删除顶部刷新按钮，作品和文件列表统一使用下拉刷新，减少重复入口；Android 回收站返回不受影响。

## Android 0.6.6 / iPhone 0.6.6 / Windows V4.1.2 - 2026-08-01

- 双端首页移除重复的“作品 + 总数”，分类数量成为唯一总量入口；iPhone 增加文件浏览入口和双箭头刷新图标。
- Android 统一为 iPhone 式浅灰、白色内容面和绿色主色，增加分类滑块、内容过渡、边缘阻尼回弹及下拉刷新。
- iPhone 设置重排，移除早期工作方式和导入入口，补齐前台剪切板同步、截图识别、自动发送、主设备选择与主设备接收，并修复长说明拥挤。
- 软件说明与双端交互规则同步更新；iOS 平台限制在界面内明确说明。

## Android 0.6.5 / iPhone 0.6.5 / Windows V4.1.2 - 2026-08-01

- iPhone 首页补齐四个分类筛选：全部、转化、泛流量、未分类，并显示各分类作品数量；修复筛选后点卡片可能打开未筛选列表中同位置作品的问题。
- iPhone 分类栏使用紧凑的系统分段控件，在窄屏上缩短标签但保持完整分类语义；总数徽标始终显示全部作品数。
- Android 自动整理改成两条紧凑设置行，点开选择立刻、1/3/6/24 小时或自定义 0～720 小时，选择后即时保存，不再长期占用设置页。
- Android 设置开关改成标题与说明分层显示：标题保持正常字号，说明使用更小、更浅的文字并按内容自动增高，修复窄屏和大字体下说明被截断的问题。
- iPhone 自动整理同步相同的常用预设、自定义范围和即时保存交互；彻底删除时间仍不能早于移入回收站时间。
- Redmi Note 8 已从正式 0.6.4 同签名覆盖到 0.6.5 候选版，作品、目录和设备名保留；分类、设置排版、预设选择、130% 系统字体与恢复默认值完成实体操作。
- 三端自动构建与协议闭环最终全部通过；正式安装包、源码和校验清单已发布，Android 更新索引已切换至 0.6.5/code 43，公网 APK 重新下载校验及覆盖安装通过。

## Android 0.6.4 / iPhone 0.5.3 / Windows V4.1.2 - 2026-07-31

- Android 悬浮剪切板首次改为可用屏幕宽高各 50%，默认位于状态栏下方并水平居中；保持拖动、缩放和位置尺寸记忆，点悬浮窗外部收起面板且不吞掉原应用点击。
- 悬浮球长按 5 秒的暂停期限统一为 1 分钟、30 分钟和永久关闭；临时期限到期自动恢复。
- 三端剪切板消息增加来源、消息 ID 和跳数，接收端按一小时窗口去重并转发；Android 自动写入系统剪切板，iPhone 只在前台读写，Windows 轮询本机系统剪切板。
- Android 新截图可自动发送到指定主设备；没有启用自动目标时保留待确认记录，进入应用再选择设备。发送端截图识别、自动发送和接收端允许接收分别独立控制。
- V2 任务增加兼容旧客户端的中转元数据。Android、iPhone、Windows 可在可信局域网或热点拓扑中暂存并转发，队列一小时过期，按消息 ID 去重和跳数限制防止循环。
- Android 更新下载统一使用 `album-Android-<version>.apk`。下载完成后校验 SHA-256、APK 解析、包名、版本号和签名，再提供安装；损坏或不匹配的包删除并重下。
- Android 0.6.4/code 42 已通过本机单元测试、Release 构建和 Release Lint；三端实体设备链路与覆盖安装结果以连接设备后的逐机记录为准。

## Android 0.6.3 - 2026-07-30

- 修复最新剪切内容过长时撑满悬浮窗口、导致固定常用语无法下滑到达的问题。
- 长剪切默认折叠为 3 行摘要并显示“展开”；展开后可查看完整内容，顶部按钮随时切换为“收起”。新剪切到达时自动恢复折叠，避免继承上一条的展开状态。
- 悬浮窗和普通剪切板页面都改为单一纵向滚动区域，最新剪切、固定常用语和新增入口按同一滚动链排列，不再使用互相争抢手势的嵌套滚动视图。
- 悬浮球长按 5 秒弹出关闭时长，可选 30 秒、5 分钟、1 天或永久关闭；临时关闭到期自动恢复，永久关闭后可从设置重新开启。

## Android 0.6.2 - 2026-07-30

- 修复同一手机离线后以原网络地址重新上线时，不会重新补齐最新剪切板和固定常用语的问题。
- 点击固定常用语后立即把它写成最新剪切并同步在线手机，不再等下次打开悬浮窗；剪切板物理存储也只保留最新一条，避免历史和墓碑长期堆积。
- 悬浮面板改为可获取焦点，并在窗口稳定后再读取剪切板，提高 Android 10+ 跨应用读取成功率；点右上角新增时先收起悬浮窗，避免遮住编辑页。
- 剪切板文件读写改为跨实例统一串行，修复接收、编辑与悬浮窗并发写入可能互相覆盖的问题；同一时间戳的多机冲突使用确定规则收敛到同一版本。
- 更新下载会识别正在下载和已经校验完成的同版本 APK，避免重复下载；覆盖安装成功后自动移除本 App 跟踪的旧安装包，失效任务允许重新下载。

## Android 0.6.1 - 2026-07-30

- 移除手机主界面左下角的重复剪切板按钮，只保留默认开启的系统悬浮圆点入口。
- 悬浮圆点支持在整块屏幕内横向、纵向自由拖动，首次默认位于屏幕上方约四分之一处，并记住位置。
- 点击圆点直接打开带边框的悬浮剪切板，不再强制跳转普通页面；悬浮窗可拖动，右下角可拉伸大小，并持久保存位置和尺寸。界面顶部只显示最新一条剪切内容，下面显示固定常用语，右上角可新增。
- 从电脑端正式《前端私聊承接与拉群 SOP》写入 8 条当前推荐前端话术；只在缺失时初始化，用户修改或删除后不会被升级反复覆盖。
- 剪切内容只向当前在线手机同步最新一条，不灌入整套历史；固定常用语持续同步全部增删改，设备重新上线后自动补齐当前版本。

## Android 0.6.0 - 2026-07-29

- Android 系统分享面板新增“相册”目标，可把其他应用中的图片、文字和文件直接发到同一 Wi‑Fi 下的在线设备。
- 区分“普通作品/文件传送”和“图片＋文字直接分享”：普通传送始终按真实文件夹落盘，不再误触发发布分享；只有系统分享进来的图片＋文字会进入分享准备。
- 原“流量转化”入口升级为共享剪切板：左侧剪切板记录、右侧常用语，同一组已发现手机自动同步新增、修改和删除；悬浮“贴”按钮默认开启，可在设置关闭。
- 新增截图发送提醒，可指定主设备；新截图出现后以不遮挡操作的系统通知询问，点“发送”后显示发送进度和结果。
- 顶部分类显示“全部、转化帖、发流量帖、未分类”及各自数量；接收的平铺内容统一整理进真实文件夹，继续以用户授权文件夹为准自动对账。
- 保留每次打开自动检查并下载更新、明确 `.apk` 文件名、SHA-256 校验和系统安装确认；手机版不加入坚果云。

## Android 0.5.10 - 2026-07-29

- 修复部分 Android / HarmonyOS 手机通过系统下载更新后文件名没有 `.apk` 后缀的问题；现在明确保存为 `相册-Android-版本号.apk`，无需手动改名。
- 保留每次打开自动检查、发现新版自动下载、SHA-256 校验和系统安装确认流程。

## Android 0.5.9 - 2026-07-29

- 手机作品页新增“全部、转化帖、泛流量帖、未分类”四个顶部筛选，默认显示全部；作品按 `[转]`、`[泛]` 目录自动归类。
- 修复 HarmonyOS / EMUI 文件索引失效导致整库显示 `FileNotFoundException`、长按删除失效和回收站无法清空的问题；单条坏索引不再中断扫描，并以真实 Lark 目录兜底。
- 清空回收站会同时移除外部原文件夹和 App 记录，并兼容华为删除图片时即时生成的 `.hwbk` 备份文件。
- 已分享作品显示分享次数与自动删除倒计时；默认 1 小时，支持立刻、1/3/6/24 小时预设及 0～720 小时自定义。
- 每次启动可自动检查并下载更新；接收文件使用持久进度通知，完成时显示文件数和耗时，失败详情沉淀到操作记录。
- 首页增加“流量转化”入口，连接电脑端转化助手以浏览 SOP 和复制话术；坚果云同步继续只由电脑端负责，不进入手机版设置。
- 华为 P30（Android 10 / HarmonyOS 4）完成覆盖安装、27 个作品读取恢复、分类、长按删除、真实目录移动及回收站清空验证。

## Windows V4.1.1 - 2026-07-27

- 修复已发现设备 15 秒后被移除、点击刷新又先清空列表的回归；刷新改为保留已知设备并立即发送探测。
- 将局域网设备保留窗口延长到 10 分钟，兼容 Android/iOS 进入后台后广播被系统节流的情况。
- 单窗口比例改为素材区约 38%、设备区约 62%，设备卡片重新成为主操作区域。
- 标题说明重新明确“把任意文件或文件夹拖到设备卡片”，原拖放、设备备注、作品数、通道开关和传送能力均保留。

## Windows V4.1.0 - 2026-07-27

- 把素材库、素材目录、归档目录、在线设备和传送状态合并进同一个主窗口，移除独立素材库弹窗。
- 主界面改为清晰的左右工作流：左侧选择作品，右侧选择设备；“传送选中素材”成为唯一绿色主操作。
- 保留任意文件或文件夹传送、拖放、设备刷新、取消、诊断和安全归档能力；系统目录选择器与归档确认按需出现。
- 增加最小窗口尺寸，避免缩小时素材操作和设备区域互相挤压或被裁切。

## Windows V4.0.0 - 2026-07-27

- Windows 中控新增独立“素材库与安全归档”窗口，可设置任意素材目录、读取一级作品文件夹、刷新列表，并把选中项传给主窗口当前设备。
- 成功传送记录由 TSV 升级为 Windows 自带 SQLite 数据库；旧 TSV 自动且只迁移一次，保留原文件，并保留初始化失败时的旧记录降级路径。
- 数据库新增内容、传送、设置、归档事件与 `ready/archive_ready/archived` 状态；重复提醒继续沿用原内容指纹，不因改名而失效。
- 新增“已使用并归档”：压缩后检查 ZIP 结构、条目数和 SHA-256，拒绝覆盖同名包；成功后将原文件夹移入 Windows 回收站，不做不可恢复删除。
- Windows CI 新增 SQLite 迁移、幂等导入、记录查询、设置读写与归档状态测试，并把测试失败纳入 Windows 构建失败条件。
- 远程 HTTP 闭环对本地 R2 下载启动瞬态 500 增加有限重试；协议、类型检查和真实 Worker/DO/R2 闭环仍必须全部通过。

## 未发布 - 远程传送服务基础

- 新增独立 `remote-relay/` Cloudflare Worker：使用 Durable Object 保存设备组与真实在线会话，R2 只保存短期密文。
- 工作区 ID 与管理电脑公钥绑定；成员使用电脑签发凭证和设备签名挑战登录，伪造管理电脑不能替换根身份。
- 增加远程开关、设备撤销、会话立即失效、WebSocket 在线通知、密文上传/提交/下载/取消/确认和最长 24 小时过期清理。
- 中继包升级到 0.1.1，增加 HTTPS 在线心跳、收件箱/发件箱轮询和结构化请求日志；移动端即使不能长期保持 WebSocket，也能上报真实在线状态并拉取已提交任务。
- 云端清单不含文件名、用户路径、明文或明文密钥；接收成功 ACK 后立即删除密文。
- 协议测试增至 10 项，覆盖在线心跳与收发箱可见性；Cloudflare API 类型检查、依赖高危审计和 Wrangler 部署预检通过。第二备用 CI run `30266289195` 已实际启动 0.1.1 Worker，并跑通 Durable Object、R2 密文上传/下载与 ACK 删除；同一 run 的 Windows、Android、iPhone 构建全部成功。
- 同一 CI run 的 Windows、Android、iPhone 原有构建全部成功；本机 Cloudflare 运行时仍因 Windows `workerd` 访问冲突无法启动，Linux CI 是当前集成运行证据。
- 本阶段未部署云端、未接入三端客户端、未完成异网真机传送，正式版继续隐藏“远程”标签。

## Windows V3.9.1 - 2026-07-26

- 修复同一台 Redmi K60 因 WiFi 上报硬件型号、USB/MTP 上报市场名称而显示为两张卡的问题；合并后保留电脑备注和作品数，并在右侧同时显示 WiFi、USB。
- 同步兼容 Redmi 9A、Redmi 13 已知硬件型号别名；不恢复“只有一台安卓就猜测合并”的危险逻辑。
- 设备卡右侧改为独立通道标签，严格按 USB → WiFi → 远程排列，只显示当前真实可用的路线；未部署的远程服务不会显示。
- 增加设备通道诊断状态，记录 USB 可写、WiFi 地址与远程会话是否真实连通，便于处理多手机、MTP 名称差异和高 DPI 界面问题。
- 在 Redmi K60 实机完成 Windows → 手机 USB 和 WiFi 两条路线落盘测试；接收文件均位于 `Download/Lark`，电脑与手机 SHA-256 一致，测试文件验收后已删除。

## Android 0.5.8 / iPhone 0.5.2 / Windows V3.9 - 2026-07-26

- Windows 设备卡片改用安卓机器人与手机轮廓标识；电脑备注优先显示，下一行保留手机名称和原始型号。
- 卡片只显示当前真实可用的 USB、WiFi、远程通道，不再重复显示“在线”；传送期间显示对应通道和进度。
- 设备右键菜单新增“传送方式”，可分别允许或关闭 USB、WiFi、远程传送；设备首次登记默认允许，手动关闭后长期记住。
- 当前发送自动按 USB → WiFi 选择最快可用通道；后续远程服务接通后再追加 P2P 直连与加密中继兜底。
- Android 与 iPhone 在同一局域网发现电脑后持久记录电脑身份，为远程公钥登记与设备撤销保留升级入口。
- Android 与 iPhone 的设备选择列表显示 WiFi 通道，发送进度明确标注“WiFi 传送中/完成”；远程接通后同一位置显示“远程直连”或“远程中继”。
- 首次自动登记时，电脑提示设备已登记，手机提示“电脑已确认传送权限”；通道开关、传送中、完成与撤销均使用一致的短文字状态。

## Android 0.5.7 / Windows V3.8 - 2026-07-26

- Windows 新增内容指纹和按设备成功传送历史；重复拖入时显示上次时间并默认跳过，允许明确重传。
- Windows 新增 Android MTP 与 iPhone 文件共享 USB 通道；USB 可写时优先，失败自动回到 Wi‑Fi。只连接充电/调试接口时会显示待切换文件传输。
- 只检测到充电/调试接口时不再显示底层驱动名，统一使用“安卓手机”和“切换为文件传输”的中文提示。
- 无法从 USB 接口确认真实设备名时不再猜测并合并到唯一的 Wi‑Fi 安卓设备，避免多手机环境下把 USB 状态或发送目标关联错设备。
- 同一台 Android 开启 MTP 后，按 USB 厂商和设备硬件标识合并可传文件接口与底层接口，避免一台手机显示两张卡。
- 优先读取 USB 总线提供的真实机型名；Windows 设备路径格式不一致时仍可用真实名称将 MTP 与底层接口合并。
- 过滤 Windows 设备管理器残留的无名称且不可打开的便携设备条目；真实 MTP 打开失败时写入不含设备标识的诊断原因。
- 部分 MIUI 同时返回“有名称的底层 USB”和“无名称但可写的 MTP”；仅在两边都唯一时配对为一张真实设备卡，多设备或多匿名接口时不猜测。
- Android 以所选外部目录为来源真相，清除文件管理器已删除但私有库仍保留的幽灵作品和回收项；网络接收且无外部来源的内容不受影响。
- 厂商文件服务单次空列表不触发删除，缺失来源必须经过延时二次确认。
- 红米 K60 正式包覆盖到 0.5.7/code34，现有授权和数据保留；真实目录与首页均为 24 个作品，私有幽灵回收项从 2 个归零。

## Android 0.5.6 / iPhone 0.5.1 - 2026-07-26

- Android 的作品分发与文件浏览改为同一首页内原地切换：冻结顶栏、传送、刷新、回收站和设置位置不变，只替换下方内容；返回键先退出文件夹层级或切回作品，不再像进入另一套页面。
- 顶部标题、数量、圆形按钮和间距重新压缩；模式、传送、刷新、回收站和设置点击后增加简短文字反馈，兼顾纯图标的简洁与可理解性。
- 文件浏览改用接近系统文件管理器的文件夹、图片、文档、PDF、压缩包、视频、音频和普通文件图标；ZIP 使用“文档 + 拉链”造型，不再与垃圾桶混淆。
- Android 与 iPhone 将旧版自然日记录迁移为北京时间下的精确时间：昨天及更早的旧记录立即按当前规则整理；当天旧记录从升级时起保留完整 1 小时，避免猜测分享时刻。
- iPhone 的自动移入回收站与彻底删除统一为独立 1～10 小时设置，默认均为 1 小时；前台每分钟检查，重新打开应用立即检查。旧回收站中没有状态记录的文件夹从升级时起保留 1 小时后再删除。
- 保持 Android 包名、签名、iPhone 状态文件和目录授权兼容；覆盖升级不重置设备名称、作品、分享次数或所选文件夹。

## Android 0.5.5 - 2026-07-26

- “复制并分享”改为点击后立即、且只记一次，不再依赖不同厂商不稳定的系统分享目标回调；已经发起过的作品再次分享前会明确提醒。
- 修复 Android 10 同一隐藏作品被 SAF 与旧存储兼容通道分别导入的问题；升级时以“文案 + 图片内容指纹”识别当前列表与回收站中的同一作品，并清理应用私有副本内的重复图片，不修改用户原作品。
- 首页新增“小红书笔记 / 文件浏览”模式切换；文件模式可浏览已授权根目录、进入子文件夹、查看普通文件并交给系统打开。
- 默认在首次分享 1 小时后进入回收站并从文件管理中彻底删除；设置可分别填写 1～10 小时，彻底删除时间不得早于回收时间。
- MediaStore 分享副本、接收暂存和传送压缩临时文件统一在生成 1 小时后清理，不触碰作品原文件。
- 保持 `com.zwm.gallery`、原签名和原数据结构覆盖升级；旧作品不会因新清理规则追溯删除。

## Android 0.5.4 - 2026-07-23

- 修复部分 HarmonyOS / EMUI 系统选择器先返回页面、后送达目标应用回调时，图片已经分享但作品次数没有增加的问题；增加短暂回调宽限，不影响 VIVO 分身冷启动等待逻辑。
- 作品卡片从 190dp 收紧为 174dp，减小内边距和卡片间距；四角从明显投影改为 1dp 柔和阴影加暖灰细描边，已分享与多选状态继续保持清晰。
- 重写设置中的“软件说明”，按核心场景、作品工作流、跨设备传送和设计思路介绍当前完整能力，不再以权限限制作为产品介绍主体。
- 保持 `com.zwm.gallery`、原签名和现有数据结构，可直接覆盖升级；目录授权、作品、分享次数和回收站不会因本次升级重置。

## Android 0.5.3 - 2026-07-23

- 撤掉 `REQUEST_INSTALL_PACKAGES`，相册不再申请“安装其他应用”能力，也不再直接打开 APK 安装器。
- 设置里检查到新版后交给 Android 系统下载；下载完成时点系统通知，再由系统确认安装，不跳 GitHub 页面。
- 系统下载完成后仍按发布索引核对 SHA-256；校验不一致会删除下载文件，并在下次打开相册时提示重新下载。
- CI 新增正式 APK 权限回归：只要重新带入安装包请求权限或 Debug 标志，构建即失败。
- 继续沿用 `com.zwm.gallery`、原签名和数据结构，可覆盖升级并保留目录授权、作品、分享次数和回收站。

## Android 0.5.2 - 2026-07-23

- 修正 0.5.1 顶部作品数字被弹性标题区域推远的问题，数字现在紧跟在“作品”右侧。
- 传送、刷新、回收站和设置保留 48dp 点击范围，视觉改为真正圆形，并为每个圆形入口增加一致间距，减少拥挤感。
- 继续沿用同一包名、签名和数据结构，可覆盖升级并保留现有作品状态和回收站。

## Android 0.5.1 - 2026-07-23

- 顶部冻结工具栏增加 Android 15+ 状态栏、导航栏和水滴/刘海安全区适配；横屏时同时避开左右挖孔区域。
- 传送、刷新、回收站和设置扩大为 48dp 点击区域，并重新平衡标题、数字和顶部留白，兼顾 Redmi 9A 等较窄屏幕。
- 正式交付改为同签名 Release APK，关闭 `debuggable`；包名、签名和数据结构不变，可覆盖升级并保留目录授权、作品、分享次数和回收站。
- 本机已完成单元测试、Release 编译、Release Lint、APK 签名与清单核对；水滴屏实体手感和厂商安全提示等待连接真机复核。

## 0.5.0 / Windows V3.7 - 2026-07-23

- 手机作品卡片增加文件夹式内容预览：文案单独显示，图片按原作品常用 3:4 比例两列排列；TXT、ZIP、JSON、PDF 等其他文件显示文件名、类型和大小，并可交给系统预览。
- 多选后使用垃圾桶、三点连线分享、小飞机三个图标，可将所选图片移入作品内图片回收站、分享到其他应用或传送到同网络设备；图片回收站保留 7 天并支持恢复。
- Android 分身分享等待系统媒体写入完成，并识别 VIVO 分身冷启动后分享目标提前返回的情况；未真正打开目标时不增加分享次数。
- iPhone 图片缩略图和大图改为按设备尺寸降采样，降低 iPhone 6 打开多图作品时的内存压力；同一 IPA 继续兼容 iOS 12 与新系统。
- Windows 使用手机端同款应用图标，设备名前增加 Android / iPhone 标识，统一圆角设备卡和操作按钮，压缩底部状态区并继续隐藏本机设备。
- Android、iPhone 和 Windows 均保持原包名/签名/数据结构覆盖升级，作品、目录授权、分享次数和现有回收站不重置。

## Android 0.4.9 - 2026-07-22

- 修复部分旧 Android / MIUI 在 Wi‑Fi 短暂中断后，HTTP 接收仍正常但 UDP 设备发现线程永久退出的问题。
- 设备发现遇到临时网络错误后自动重建，并记录“正在恢复 / 已恢复”诊断状态，不再要求重启应用。
- 新增发现循环恢复单元测试；保持原包名、原签名和覆盖升级数据兼容。

## Android 0.4.8 - 2026-07-20

- 长按作品进入批量选择模式，所有作品卡片显示复选框，可继续勾选多个作品；右下角垃圾桶一次移动全部选中项。
- 顶部标题实时显示“已选 N 个”；取消最后一项或按返回键退出多选，不触发分享或删除。
- 批量移动逐项执行，成功项进入现有回收站，失败项保持选中并显示成功/失败数量，不用一次失败回滚已经安全完成的作品。

## 0.4.7 / Windows V3.6 - 2026-07-20

- Android 10+ 分享图片改走系统 MediaStore 临时分享区，并对可见分享目标补充显式读取授权，改善小米、VIVO 应用分身无法打开或卡住的问题；Android 8/9 保留原私有分享通道。
- 临时媒体副本只为跨空间分享使用，原图、作品状态和回收站不变；副本在下一自然日启动分享时清理。
- Android 首页顶部工具栏固定，不再随作品列表滚动；长按作品可选中并通过右下角垃圾桶移入现有回收站，真实文件夹移动失败时不伪装成功。
- Windows、Android、iPhone 和共享技能统一把已知数量显示为“设备名（作品数 25）”；旧客户端数量未知时只显示原设备名。
- Android 增加覆盖升级数据回归，验证作品、分享次数和回收站记录在同包名、同签名原地升级后保留。

## 0.4.6 / Windows V3.5 - 2026-07-20

- Android 与 iPhone 把当前扫描得到的作品数量加入局域网设备状态；只公布整数，不读取或发送作品名称、文案、图片和路径。
- Windows 每台手机卡片在在线状态后显示作品数，例如“在线 · 15”；旧版手机继续正常显示在线，但不显示未知数字。
- Android 与 iPhone 的互传设备列表同步显示对方作品数。
- 共享技能新增作品数查询，可回答“每台手机现在有多少作品”，也可按设备名称单独查询。
- 扩展发现协议和 `/v2/info`，保持旧客户端向后兼容。

## 0.4.5 - 2026-07-20

- 修复接收重名目录后生成的“相册回收站 (1)/(2)”被当成作品库继续递归扫描，造成旧作品重复显示的问题。
- Android 与 iPhone 使用同一判定规则：只排除真实回收站及系统生成的数字重名副本，不误伤“相册回收站教程”等普通文件夹。
- iPhone 6 实体安装继续保持 iOS 12 兼容，设置页保留 App 内“选择作品文件夹”。

## 0.4.4 - 2026-07-20

- Android 更新改为 App 内直接下载 APK，增加进度、取消、SHA-256 校验、安装权限引导和清晰诊断，不再跳转发布网页。
- iPhone 更新检查不再跳转网页，并明确侧载版需要电脑重新签名覆盖。
- iOS 12 增加 App 内“选择作品文件夹”，可从相册接收目录及其子目录中选择递归扫描根目录；iOS 13+ 保留系统外部文件夹选择器。
- 同一 Android 包和同一 IPA 继续覆盖 Redmi、Huawei、iPhone 6 和较新 iPhone，不按机型分叉。

## 0.4.3 - 2026-07-19

- iOS 12 固定作品库增加文件/ZIP 导入，支持标准 Deflate ZIP。
- Android 与 iPhone 增加默认关闭的声音通知和震动开关。
# Android 0.6.17 - 2026-08-10

- `/v2/info` 新增向后兼容的 `workCounts` 聚合分类库存；保留原 `workCount` 总数。
- 分类统计复用作品库现有 `category`，覆盖刷新、导入、接收提交和自动清理后的更新路径。
- 新增分类统计单元测试；旧客户端没有分类字段时保持“未知”，不将总数冒充分类数。
