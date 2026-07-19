# Device Share Protocol V2

## 目标

- 同一局域网自动发现，无人工配置。
- Windows 直接向安卓手机传输，不经过电脑端 HTTP 中转服务。
- 设备 ID 在局域网与未来云端模式中保持一致。

## 端口

- UDP `45834`：设备发现。
- TCP `45833`：Android / iPhone 接收端 HTTP 服务。

## 发现

Windows 每两秒向 UDP 广播地址发送：

```text
ZWMDS2_DISCOVER
```

手机接收端回复：

```text
ZWMDS2_HERE|2|deviceId|httpPort|base64url(name)|base64url(model)|base64url(state)|taskId
```

设备超过 9 秒没有刷新即视为离线。

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

## 安全边界

V2 局域网模式按产品要求不做配对和鉴权，只应在可信任的专用 Wi‑Fi 中使用。未来远程模式必须使用工作区身份、设备密钥与加密传输，不能把无鉴权的局域网端口直接暴露到公网。
