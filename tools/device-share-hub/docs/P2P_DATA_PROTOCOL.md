# P2P DataChannel 文件传输协议

此协议用于认证后的 `webrtc-datachannel-v1` 会话。Windows 原生发送端的自动模式在 P2P 失败后进入现有 HTTPS 中继。Android 0.8.145 (256) 恢复 `work` 收件，应用更新沿用 HTTPS 中继。

## 传输顺序

```text
Cloudflare 认证信令 → WebRTC DataChannel 建连 → manifest → binary chunks → complete → ack
```

Cloudflare 只转发短时 SDP/ICE 信令，不接收文件字节。DataChannel 使用 ordered + reliable 模式，底层 DTLS/SCTP 负责链路加密与可靠有序传输；应用层仍按 SHA-256 校验完整作品。

## 文本帧

所有文本帧都是 UTF-8 JSON，必须有 `v: 1` 和 `kind`：

```json
{"v":1,"kind":"manifest","transferId":"p2p_...","senderDeviceId":"...","recipientDeviceId":"...","objects":[{"index":0,"bytes":123,"sha256":"64位十六进制","name":"album-folder-作品.zip","mime":"application/zip"}],"totalBytes":123}
```

接收端必须校验：设备身份与已认证信令会话一致、对象序号不重复、大小为正、SHA-256 为 64 位十六进制、文件名不含路径分隔符、总字节数不溢出。

发送端逐个对象发送以下文本帧：

```json
{"v":1,"kind":"object-start","index":0}
{"v":1,"kind":"object-end","index":0}
{"v":1,"kind":"complete","transferId":"p2p_..."}
```

接收端写入缓存、完成全部大小/SHA-256 校验并保存到作品库后回复：

```json
{"v":1,"kind":"ack","transferId":"p2p_...","ok":true,"objects":1,"bytes":123}
```

失败回复 `ok:false` 并关闭 P2P 会话；发送端不得把失败当成已送达，进入 HTTPS 中继路径。

## 二进制帧

二进制帧使用大端序，最小 20 字节头：

| 偏移 | 大小 | 字段 |
|---:|---:|---|
| 0 | 4 | ASCII `DSHP` |
| 4 | 1 | 版本 `1` |
| 5 | 1 | 类型 `1 = chunk` |
| 6 | 2 | 保留，必须为 0 |
| 8 | 4 | 对象序号 |
| 12 | 8 | 对象内偏移 |
| 20 | N | 原始文件字节 |

每个 chunk 不超过 48 KiB。对象内偏移必须连续，不能超过 manifest 声明的大小；接收端遇到越界、空洞、未知对象或校验不通过时立即失败并清理临时文件。

## 去重与回退

`transferId` 由发送端生成，接收端记录成功导入的 ID，同一 ID 重试只补 ACK。当前 Windows 的直传 ID 和新建 R2 传输 ID 可能不同，跨路径去重仍待验证。ICE failed、DataChannel closed、manifest/校验错误会让发送端进入中继兜底。

## 当前明确边界

- 不做应用层端到端加密；作品是用户明确指定的公开内容。
- 不使用截图、剪贴板、悬浮窗或无障碍权限。
- Android 候选已用 Windows Wi-Fi 的协议测试发送器和小米蜂窝完成 DataChannel、作品库导入及 ACK。Windows 原生面板、iPhone 蜂窝和同次发送自动回退仍需单独验证。
