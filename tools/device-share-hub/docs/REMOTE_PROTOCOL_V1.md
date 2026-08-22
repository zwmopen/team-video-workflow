# 远程传送协议 V1

状态：普通公开作品链路已实现并有本地协议闭环测试；Android/iOS 已接入对象下载、作品库导入和 ACK。Windows 原生面板按钮化、正式云端部署和真机跨网验收仍未完成。

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
- 云端只看到设备标识、密文字节数、密文哈希和加密密钥包，不看到文件名、路径、文件明文或明文密钥；
- 每台设备使用独立私钥，不能把同一把万能密钥写入所有安装包；
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

## 端到端密文

每次传送：

1. 发送端生成 32 字节随机内容密钥；
2. 每个对象生成独立 12 字节 nonce，使用 AES-256-GCM 加密；
3. 对接收设备的 ECDH P-256 公钥生成一次性发送公钥；
4. ECDH 共享秘密经 HKDF-SHA256 派生包装密钥；
5. 包装密钥使用 AES-256-GCM 加密“内容密钥 + 文件名/路径/明文哈希/对象 nonce 清单”；
6. 服务端只保存包装后的 `encryptedKeyPackage` 与对象密文。

HKDF `info` 必须包含协议版本、工作区、发送设备、接收设备和随机 `keyContext`；对象 AES-GCM 的 AAD 必须包含协议版本、`keyContext` 与对象序号，防止跨任务替换。

接收端下载后按以下顺序处理：

```text
密文 SHA-256 → 解开接收端专用密钥包 → AES-GCM 验证 →
明文 SHA-256 → 安全暂存 → 原子提交到接收目录 → ACK
```

只有本地落盘和明文校验都成功后才能发送 ACK。ACK 后服务立即删除全部 R2 密文；失败或取消也删除；最长 24 小时自动过期。

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
| `POST /v1/transfers` | 创建密文传送清单 |
| `PUT /v1/transfers/{id}/objects/{index}` | 上传单个密文对象 |
| `POST /v1/transfers/{id}/commit` | 全部密文上传后通知接收端 |
| `GET /v1/transfers/{id}` | 读取加密清单与状态 |
| `GET /v1/transfers/{id}/objects/{index}` | 接收端下载密文 |
| `POST /v1/transfers/{id}/ack` | 接收成功并删除云端密文 |
| `POST /v1/transfers/{id}/cancel` | 取消并删除云端密文 |

### 断点与重试语义

- `GET /v1/transfers/{id}`、`GET /v1/inbox` 和 `GET /v1/outbox` 会在不暴露文件名或明文的前提下，为每个对象返回 `uploaded`、`uploadedAt`，以及任务级 `uploadedObjectCount`、`uploadedCipherBytes`、`nextObjectIndex`。
- 发送端重启或网络中断后，应先读取任务状态，再从 `nextObjectIndex` 或首个 `uploaded=false` 的对象继续；不能直接创建第二个任务。
- 对同一任务、同一对象、同一密文哈希重复 `PUT` 时，中继会校验 R2 中现存密文的字节数和哈希元数据并返回 `reused=true`，不会重复写入对象。
- 对象级幂等不等于整文件字节级续传；大对象的分片续传留在后续客户端接入阶段，当前 V1 仍以一个加密对象为最小提交单位。

## 当前限制

- 当前已实现受设备会话保护的 WebRTC/ICE 信令协商基础，但文件数据面仍走现有中继，WebRTC/ICE 远程直连传输尚未实现；
- 服务尚未部署，三端尚未生成系统级设备密钥或调用这些入口；
- Windows 本机 `workerd` 在启动本地 Cloudflare 运行时时出现访问冲突；第二备用 CI run `30252544159` 已在 Linux 实际跑通 Worker、Durable Object、R2 上传/下载和 ACK 删除，本机环境兼容仍待后续修复；
- 没有手机流量与异地 WiFi 的实体证据前，正式界面继续隐藏“远程”标签。
