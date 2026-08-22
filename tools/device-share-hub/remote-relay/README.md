# 远程传送中继

这是“素材投送中控 / 相册”远程通道的服务端。它不恢复旧网页面板，也不改变 USB 和局域网 WiFi 的既有流程。

当前阶段提供：

- 首次电脑管理身份固定；
- 电脑签发的设备组成员凭证；
- 设备签名挑战与 24 小时短期会话；
- 在线 WebSocket 通知；
- 适配移动端后台限制的 HTTPS 在线心跳，以及收件箱/发件箱轮询；
- 普通公开文件清单、上传、下载、取消和接收确认；旧加密清单仍兼容；
- 手机下载并校验普通作品包、写入作品库后才发送 ACK；
- 接收确认后立即删除 R2 临时对象；
- 最长 24 小时自动过期清理；
- `mode: plain` 会保存安全文件名、MIME、字节数和 SHA-256；这是公开作品传送，不做应用层端到端加密。传输仍强制 HTTPS，云端对象只按短期任务保存并在 ACK/取消/过期后删除。

2026-08-21 已增加任务状态与幂等重试：`/v1/transfers/{id}`、`/v1/inbox`、`/v1/outbox` 返回每个密文对象的上传状态和下一待传索引；同一任务对象再次上传时会先核对 R2 密文的大小与 `cipherSha256`，一致则返回 `reused=true`，避免断线重试重复写入。

桌面发送：

```powershell
node scripts/send-public-work.mjs --endpoint https://relay.example.com --token <会话令牌> --recipient <手机设备ID> --path D:\作品集
```

该脚本是桌面端的普通公开作品发送入口：目录会先打成普通 ZIP，随后创建任务、上传对象并提交。会话令牌由现有设备登记/登录流程提供，不写入仓库。

当前仍需要单独验收：

- Windows 原生图形面板已经接入自动登记和中继兜底；该脚本仍保留作协议诊断与无界面烟测入口；
- 尚未完成手机流量与异地 WiFi 的实体跨网络传送；
- 三端原生 P2P DataChannel 数据面已经接入；本阶段远程传送仍以 HTTPS 普通公开文件中继作必达兜底。当前已实现受设备证书授权的 WebRTC DataChannel 信令会话，P2P 直连失败时由 Windows 自动回退中继。

## Cloudflare 正式部署

2026-08-21 已部署到 Cloudflare 账号，当前正式 Worker 地址为：

`https://zwm-device-share-relay.zwmrpg.workers.dev`

- Worker：`zwm-device-share-relay`
- Durable Object：`WorkspaceRelay`
- R2：`zwm-device-share-relay`
- 部署版本：`7da4be21-632d-4e74-9a75-4f6d413f8e0a`

当前账号没有可绑定的活动 Cloudflare Zone，因此暂时使用 `workers.dev` 地址，没有自定义域名。部署记录和 R2 资源已由 Wrangler 实读确认；本机当前网络对该 `workers.dev` 域名存在 DNS/连接异常，不能把本机健康检查失败误判为 Worker 未部署。

## 混合传输边界

- 同一 Wi-Fi 或 USB 网络：继续优先使用现有 V2 直传，不经过 Cloudflare。
- 不同网络：当前可用的兜底链路是 HTTPS 中继；手机在已登记远程资料后每 10 秒心跳并接收任务。
- 本目录的 Worker 只实现控制面：`/v1/p2p/sessions` 负责短时 offer/answer/ICE 协商，不承载作品文件字节；Android/iPhone/Windows 客户端负责 P2P 数据面，并在直连失败时回退 HTTPS 中继。协议测试已通过，真实跨网设备验收仍需另行完成。
- Windows 原生面板已接入中继身份登记、远程在线轮询和发送兜底；手机凭证由可信局域网首次发现时自动下发，不要求用户填写 Bearer 会话。

## 本地检查

```powershell
npm install
npm run check
npm run typecheck
npm test
npx wrangler deploy --dry-run
npm run dev
```

`wrangler dev` 默认使用本地 Durable Object 和 R2 数据，不会写入正式云端。

## 部署边界

部署前需要在使用者自己的 Cloudflare 账号创建私有 R2 bucket，并通过官方登录完成授权。Wrangler 默认 OAuth 授权范围明显大于本项目实际需要，必须先由使用者确认，不得由自动化静默授权。仓库不保存 Cloudflare Token、账号、Cookie、设备私钥或工作区管理私钥。

正式客户端只显示“远程传送”；只有实际开始后才显示“远程中继”。服务部署成功本身不能替代三端客户端接入和跨网络实体验收。
