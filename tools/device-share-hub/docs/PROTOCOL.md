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
  "fileCount": 8
}
```

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

`GET /v2/info` 在手机端同时返回 `workCount`。扩展字段均为可选，旧客户端忽略尾部发现字段仍可继续发现和传送。

## 安全边界

V2 局域网模式按产品要求不做配对和鉴权，只应在可信任的专用 Wi‑Fi 中使用。未来远程模式必须使用工作区身份、设备密钥与加密传输，不能把无鉴权的局域网端口直接暴露到公网。

远程传送不复用本页无鉴权 HTTP 入口。远程设备组身份、签名挑战、端到端密文、撤销和临时中继入口见 [`REMOTE_PROTOCOL_V1.md`](REMOTE_PROTOCOL_V1.md)。
