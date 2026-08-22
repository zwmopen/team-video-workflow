# 远程传送协议 V1

状态：普通公开作品链路已实现并有本地协议闭环测试；Android/iOS 已接入对象下载、作品库导入和 ACK，Windows 原生面板已接入 P2P 优先与 HTTPS 中继兜底，Cloudflare Worker/Durable Object/R2 已正式部署。真实 Android/iPhone/Windows 跨网络传送仍待真机验收。

## 普通公开作品模式（当前产品选择）

用户已明确作品均为公开内容，因此当前远程传送使用 `mode: "plain"`，不生成、不上传应用层密钥包，也不做 AES/E2E 文件加密。HTTPS 仍是强制传输层，设备签名会话仍用于限制谁可以创建或接收任务。

电脑端把一个作品文件夹打成普通 ZIP，创建任务时提交：

```json
{
  "mode": "plain",
  "recipientDeviceId": "device_phone_01",
  "objects": [{
    "index": 0,
    "bytes": 1234,
    "sha256": "...64位十六进制...",
    "name": "album-folder-作品集[泛].zip",
    "mime": "application/zip"
  }]
}
```

接收端严格按以下顺序执行：

```text
中继对象下载 → 本地大小/SHA-256 校验 → ZIP 安全导入作品库 → 刷新库存 → ACK → 中继删除临时对象
```

任一步失败都不发送 ACK。设备重启或 ACK 失败时，任务仍会在收件箱中等待；客户端会用 transferId 持久化已完成导入，避免重试造成重复作品。

## 目标与边界

- 保留现有 USB → 局域网 WiFi 优先级，只在两者都不可用时尝试远程；
- 不暴露手机或电脑入站端口，不要求 IP、配对码或命令行；
- 当前 `plain` 模式是用户明确选择的公开作品链路：云端会看到任务中的安全文件名、MIME、字节数和 SHA-256；不把作品明文以外再做应用层加密；
- 每台设备使用独立签名/协商身份，私钥不能写入仓库或所有安装包；
- 服务部署、客户端编译和跨网络实体传送是三种不同证据。

## 设备组身份

### 密钥

每台设备首次安装生成两组 P-256 密钥：

- ECDSA P-256：签名身份挑战和成员凭证；
- ECDH P-256：为接收设备封装单次传送密钥。

私钥保留在系统密钥存储中，不进入发现广播、日志、仓库或云端。公钥使用不含 `d` 的 EC JWK。

### 工作区

管理电脑生成自签名管理员凭证。工作区 ID 固定为：

```text
ws_ + SHA-256(canonical(adminSigningPublicJwk)) 的前 32 个十六进制字符
```

因此另一把管理密钥不能抢占相同工作区。服务端第一次登记后固定管理员公钥指纹；后续管理员登录必须与这把根密钥一致。

### 成员凭证

管理员为每台 Android、iPhone 或其他电脑签发：

```json
{
  "version": 1,
  "workspaceId": "ws_...",
  "deviceId": "device_...",
  "deviceName": "用户可见名称",
  "role": "member",
  "signingPublicKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." },
  "agreementPublicKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." },
  "serial": 1,
  "issuedAt": 0,
  "expiresAt": 0
}
```

签名输入使用递归键名排序、无多余空格的 canonical JSON UTF-8。签名算法为 ES256；协议传输统一使用 64 字节 `r || s` 原始签名的 base64url 表示。Android/Windows 若系统 API 返回 ASN.1 DER，客户端层必须做严格转换。

## 登录

1. `POST /v1/challenges` 创建 5 分钟有效的随机挑战；
2. 设备使用自己的签名私钥签署完整挑战对象；
3. `POST /v1/sessions` 同时提交成员凭证、管理员签名和挑战签名；
4. 服务端核对根身份、凭证序号、撤销/远程开关和挑战后，发放 24 小时短期 Bearer 会话；
5. 前台客户端优先用短期会话连接 `/v1/socket`；无法长期保持 WebSocket 的移动端每 10 秒调用 `/v1/presence`，20 秒内收到的心跳同样视为真实在线；超过 60 秒的心跳记录自动清理。

关闭远程会立即失效该设备的全部会话并断开 WebSocket；撤销设备还会保留独立的撤销状态。客户端必须分别显示“远程已关闭”“设备离线”“设备已撤销”。

## 应用层明文作品（当前选择）

当前产品只使用 `mode: "plain"`，因为传送的是公开作品。传输仍强制 HTTPS，任务和对象只在中继短期保存，接收端完成本地校验和作品库导入后才 ACK；ACK、取消或过期后由服务删除对象。

接收端按以下顺序处理：

```text
HTTPS 下载 → 本地大小/SHA-256 校验 → 安全暂存 → ZIP 安全导入作品库 → ACK
```

只有本地落盘、哈希校验和作品库导入都成功后才能发送 ACK。P2P DataChannel 直传时使用同一份清单、分块协议、大小/哈希校验和 ACK 语义，不经过 R2；直连失败由 Windows 发送端自动切换 HTTPS 中继。

## 服务入口

| 入口 | 作用 |
|---|---|
| `GET /v1/health` | 无敏感信息的运行状态 |
| `POST /v1/workspaces/register` | 首次固定管理电脑根身份 |
| `POST /v1/challenges` | 创建签名挑战 |
| `POST /v1/sessions` | 验证设备并创建短期会话 |
| `GET /v1/socket` | 在线状态和任务通知 |
| `POST /v1/presence` | 移动端后台受限时的在线心跳 |
| `GET /v1/devices` | 当前成员与真实在线状态 |
| `GET /v1/inbox` | 接收端轮询已提交且可下载的任务 |
| `GET /v1/outbox` | 发送端查看上传中、已提交及终态任务 |
| `POST /v1/devices/{id}/remote` | 管理电脑开启/关闭远程 |
| `POST /v1/devices/{id}/revoke` | 管理电脑撤销设备 |
| `POST /v1/transfers` | 创建作品对象传送清单 |
| `PUT /v1/transfers/{id}/objects/{index}` | 上传单个作品对象 |
| `POST /v1/transfers/{id}/commit` | 全部作品对象上传后通知接收端 |
| `GET /v1/transfers/{id}` | 读取加密清单与状态 |
| `GET /v1/transfers/{id}/objects/{index}` | 接收端下载作品对象 |
| `POST /v1/transfers/{id}/ack` | 接收成功并删除云端作品对象 |
| `POST /v1/transfers/{id}/cancel` | 取消并删除云端作品对象 |

### 断点与重试语义

- `GET /v1/transfers/{id}`、`GET /v1/inbox` 和 `GET /v1/outbox` 会返回每个对象的 `uploaded`、`uploadedAt`，以及任务级 `uploadedObjectCount`、`uploadedBytes`、`nextObjectIndex`；`plain` 模式的安全文件名、MIME、大小和哈希也属于任务清单。
- 发送端重启或网络中断后，应先读取任务状态，再从 `nextObjectIndex` 或首个 `uploaded=false` 的对象继续；不能直接创建第二个任务。
- 对同一任务、同一对象、同一 SHA-256 重复 `PUT` 时，中继会校验 R2 中现存对象的字节数和哈希元数据并返回 `reused=true`，不会重复写入对象。
- 对象级幂等不等于整文件字节级续传；大对象的分片续传留在后续客户端接入阶段，当前 V1 仍以一个 ZIP 作品对象为最小提交单位。

## 当前限制

- Cloudflare Worker、Durable Object 和 R2 已部署；GitHub Ubuntu runner 已通过正式 Worker 的健康检查、设备会话、presence、P2P offer/answer/close、R2 上传/下载、SHA-256、ACK 和对象删除 E2E；
- Android/iOS/Windows 的 P2P DataChannel 数据面和 HTTPS 兜底代码已接入，但没有实体 Android、iPhone、Windows 三端同时在线时，不能宣称真实跨网络直连、自动回退、权限和文件落库验收完成；
- `mode: "plain"` 不提供应用层加密；若未来传送私密作品，必须另立加密协议和更新版本，不能把当前公开链路误称为端到端加密。
