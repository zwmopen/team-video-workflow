# Device Share Protocol V2

## 目标

- 同一局域网自动发现，无人工配置。
- Windows、Android 和 iPhone 直接互传，不经过云端或电脑 HTTP 中转服务。
- 设备 ID 在局域网与未来云端模式中保持一致。

## 端口

- UDP `45834`：设备发现。
- TCP `45833`：Android / iPhone 接收端 HTTP 服务。

## 发现

设备每两至三秒向 UDP 广播地址发送主动探测或在线消息；探测格式为：

```text
ZWMDS2_DISCOVER
```

手机接收端回复：

```text
ZWMDS2_HERE|2|deviceId|httpPort|base64url(name)|base64url(model)|base64url(state)|taskId|workCount
```

`workCount` 是可选的非负整数，表示手机最近一次本机扫描得到的作品数量。Windows 或旧客户端可发送 `-1` 或省略该字段；接收端必须把它显示为“未知/不显示”，不能当作 0。该字段不包含作品名称、文案、图片或路径。

三端同时发送全局广播和当前子网定向广播，兼容部分路由器不转发无线客户端全局广播的情况。设备超过 15 秒没有刷新即视为离线；进入传送页和手动刷新会主动重新探测。

## 投送流程

### 1. 创建任务

```http
POST /v2/tasks
Content-Type: application/json

{
  "taskId": "task-...",
  "text": "可选文案",
  "autoShare": false,
  "fileCount": 8,
  "messageId": "msg-...",
  "originId": "android-...",
  "destinationId": "windows-...",
  "contentKind": "screenshot",
  "expiresAt": 1785315600000,
  "hopLimit": 4
}
```

`autoShare` 是可选布尔值，缺省为 `false`。普通文件、文件夹和截图传送必须为
`false`，接收端只落盘；Android 系统分享目标收到“图片＋文字”时可设为 `true`，
接收端完成真实文件夹导入后才进入分享准备。旧客户端不发送该字段时行为保持安全。

`messageId`、`originId`、`destinationId`、`contentKind`、`expiresAt` 和
`hopLimit` 均为可选中转字段。没有这些字段时仍按旧版直接传送；存在最终目标且当前
设备不是目标时，可信新版客户端把完整任务保存为 `queued`，返回 HTTP `202`，再选择
直连目标或下一台新版可信设备转发。每次转发减少 `hopLimit`，同一 `messageId`
只处理一次；任务在最终目标确认接收、超过 `expiresAt` 或一小时后删除中转副本。
旧客户端可以继续发现和直传，但不能承担多级中转。

### 2. 上传文件

```http
PUT /v2/tasks/{taskId}/files/{index}
Content-Length: ...
X-File-Name: percent-encoded-utf8
X-File-Mime: image/jpeg
X-File-Sha256: ...

<raw bytes>
```

### 3. 提交任务

```http
POST /v2/tasks/{taskId}/commit
```

接收端校验文件完整后写入私有作品库，并通知手机作品列表刷新。若任务是单个 ZIP，接收端会把其中每个有效作品文件夹分别导入；不会自动弹出分享页。

## 共享剪切板同步

同一可信 Wi-Fi 下已互相发现并登记的 Android 设备可发送：

```http
POST /v2/clipboard
Content-Type: application/json

{
  "senderId": "android-...",
  "originId": "android-...",
  "messageId": "clipboard-uuid",
  "hopLimit": 4,
  "items": [
    {
      "id": "android-...-uuid",
      "kind": "clipboard",
      "text": "内容",
      "updatedAt": 1785312000000,
      "deleted": false
    }
  ]
}
```

`kind` 为 `clipboard` 或 `phrase`。删除使用墓碑记录；相同 ID 按
`updatedAt` 后写优先合并。接收端只接受当前局域网来源 IP 与已登记设备一致的请求。
这仍是可信局域网便利机制，不等同于远程模式的密码学身份认证。

新版实时剪切只保留最新一条，使用 `messageId + originId` 在一小时窗口内去重；
需要中转时逐跳减少 `hopLimit`。Android 可把收到的最新值写入系统剪切板，iPhone
仅在前台读写，Windows 使用桌面剪切板；固定常用语仍按条目 ID 同步增删改。

### 4. 取消失败任务

```http
POST /v2/tasks/{taskId}/cancel
```

## 状态

发现包中的 `state`：

- `online`：空闲。
- `receiving`：正在接收文件。
- `ready`：兼容旧版客户端的保留状态；V3 正常提交后直接回到 `online`。
- `sharing`：兼容旧版客户端的保留状态；V3 分享操作不阻塞继续接收。

`GET /v2/info` 在手机端同时返回 `workCount`。新版还可返回
`relayVersion`、`relayEnabled` 和 `screenshotReceiveEnabled`；扩展字段均为可选，
旧客户端忽略未知字段仍可继续发现和直接传送。

## 安全边界

V2 局域网模式按产品要求不做配对和鉴权，只应在可信任的专用 Wi‑Fi 中使用。未来远程模式必须使用工作区身份、设备密钥与加密传输，不能把无鉴权的局域网端口直接暴露到公网。

远程传送不复用本页无鉴权 HTTP 入口。远程设备组身份、签名挑战、端到端密文、撤销和临时中继入口见 [`REMOTE_PROTOCOL_V1.md`](REMOTE_PROTOCOL_V1.md)。
