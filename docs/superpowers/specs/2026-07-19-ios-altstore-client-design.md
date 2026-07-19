# iPhone“相册”AltStore 客户端设计

日期：2026-07-19  
项目：`tools/device-share-hub`  
状态：设计已确认；实现及实体 iPhone 验收进行中

## 1. 决策

在现有项目内新增原生 SwiftUI iOS 客户端，不重写 Android 与 Windows 客户端，不引入 Flutter、React Native、uni-app 或 Node.js 面板。应用名称继续使用“相册”。

Windows 无需安装 Xcode。GitHub Actions 的 macOS runner 负责生成未预签名 IPA；用户通过 Windows 上的 AltStore Classic/AltServer 使用自己的免费 Apple ID 侧载和续签。AltStore 是安装与续签工具，不负责源码编译。

快捷指令版并行保留，作为无需证书的最快入口；原生客户端提供更稳定的作品卡片、状态和回收站界面。

## 2. 最短使用流程

首次：

```text
AltStore 安装“相册” → 打开“相册” → 选择一次固定作品总文件夹
```

日常：

```text
手动传入并解压 ZIP → 打开“相册” → 点作品卡片
→ 自动复制 TXT + 记录打开分享次数 + 弹出系统分享面板
→ 用户选择平台、粘贴文案并发布
```

不增加手动 IP、配对码、命令行、设备管理、越狱、ADB、自动点击或伪装相册来源。

## 3. 第一版范围

- 通过系统文件夹选择器授权固定总文件夹，并持久化 security-scoped bookmark；
- 只扫描总文件夹的直接子文件夹；直接包含至少一张支持图片和一个 TXT 才是作品；
- 作品按文件夹名称显示，两列卡片布局；
- 优先读取 `文案.txt`，否则读取按名称排序的第一个 TXT；
- 图片按自然顺序进入系统 `UIActivityViewController`；
- 打开分享前复制文案并原子保存 `shareCount`、`lastShareDate`；
- 每次前台刷新时，将上一自然日及更早已分享作品移入 `_相册回收站`；
- 回收站满 7 天后在下次刷新时清理；支持手动恢复，禁止覆盖同名目录；
- 状态存储在总文件夹根目录 `_相册状态.json`；状态损坏时备份并安全重建，不删除作品；
- 设置页提供重新选择目录、当前版本、隐私与使用说明；
- 所有错误用用户可执行的中文提示展示。

## 4. 构建与安装

- 工程目录：`tools/device-share-hub/ios`；
- GitHub Actions 使用 macOS runner 和 `xcodebuild` 构建；
- CI 不保存 Apple ID、证书或描述文件；
- CI 把 `.app` 包装为 `Payload/相册.app` 后生成 IPA artifact；
- AltStore 在安装时使用用户 Apple ID 重新签名；免费账号受 Apple 的有效期和活跃 App 数限制；
- iOS 16 及以上需要用户启用开发者模式，Windows 首次安装需 AltServer 与数据线配对并启用 Wi-Fi 同步。

## 5. 安全与隐私

- 素材、文案、状态全部保留在用户授权目录，不上传服务器；
- 不注入或修改 EXIF、位置、相机型号和拍摄参数；
- 不声称分享来自系统照片 App；
- 不记录目标社交平台，不声称检测到发布成功；计数含义固定为“已打开分享 N 次”；
- 文件移动或状态写入失败时，优先保留原文件并停止后续破坏性操作；
- CI 和仓库不得提交 Apple ID、签名证书、描述文件或设备标识。

## 6. 已知边界

- Lark 等第三方文件提供器是否允许持久目录访问、移动和删除，取决于其 iOS Files 扩展，必须实体 iPhone 验收；
- 系统分享面板只能确认已打开，不能确认目标平台是否发布；
- App 未运行时不会执行次日回收或 7 天清理；
- 免费 AltStore 侧载需要周期性续签，且依赖 AltServer 可达；
- CI 成功只证明源码可编译和 IPA 可生成，不等同于 AltStore 安装或实体 iPhone 功能通过。

## 7. 验收状态规则

在实体 iPhone 上逐项验收前，所有 iOS 功能统一标记为“已实现未验收”，不得写“测试通过”。真机需至少验证：AltStore 安装、目录授权重启保持、两项作品识别、TXT 复制、多图分享、同日计数、跨日回收、恢复、7 天清理、权限撤销和 Lark 目录兼容性。
