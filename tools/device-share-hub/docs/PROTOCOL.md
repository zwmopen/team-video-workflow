# 局域网投送协议 V1

电脑端监听 TCP `45832`，手机端通过 HTTP 心跳和短轮询保持在线。所有 `/api/*` 请求都必须携带：

```http
Authorization: Bearer <pair-token>
```

## 核心流程

1. 手机 `POST /api/device/heartbeat` 注册或刷新设备状态。
2. 电脑创建任务：`POST /api/tasks`。
3. 电脑逐个上传二进制文件：`PUT /api/tasks/{taskId}/files/{index}`。
4. 电脑提交任务：`POST /api/tasks/{taskId}/commit`。
5. 手机轮询：`GET /api/device/tasks/next?deviceId=...`。
6. 手机按返回的 `downloadPath` 下载并校验 SHA-256。
7. 手机回传 `downloading`、`ready`、`shared` 或 `failed` 状态。
8. 安卓端通过只读 `ContentProvider` 将私有缓存 URI 交给系统 Sharesheet。

## 安全边界

- 配对令牌首次启动时随机生成，保存在电脑本地数据目录。
- 原始文件不经过飞书、浏览器下载目录或系统相册。
- 手机文件位于 App 私有缓存，其他应用只获得本次分享 URI 的临时读取权限。
- 服务默认只用于可信局域网，不应把 `45832` 端口映射到公网。
