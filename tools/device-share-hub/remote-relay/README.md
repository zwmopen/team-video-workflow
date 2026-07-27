# 远程传送中继

这是“素材投送中控 / 相册”远程通道的服务端。它不恢复旧网页面板，也不改变 USB 和局域网 WiFi 的既有流程。

当前阶段提供：

- 首次电脑管理身份固定；
- 电脑签发的设备组成员凭证；
- 设备签名挑战与 24 小时短期会话；
- 在线 WebSocket 通知；
- 适配移动端后台限制的 HTTPS 在线心跳，以及收件箱/发件箱轮询；
- 加密文件清单、分块上传、下载、取消和接收确认；
- 接收确认后立即删除 R2 密文；
- 最长 24 小时自动过期清理；
- 服务端只接收密文、密文字节数/哈希与接收端专用加密清单，不接收文件名、用户路径、文件明文或明文密钥。

当前还不能标记为正式可用：

- Windows、Android、iPhone 客户端尚未接入本协议；
- Cloudflare Worker、Durable Object 和 R2 尚未部署；
- 尚未完成手机流量与异地 WiFi 的实体跨网络传送；
- P2P 直连尚未实现，本阶段只有加密中继服务基础。

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
