# iOS Windows 侧载交付、自动化调度与排障手册

## 交付结论（2026-09-01 实机验收）

- **实体机型号**：`iPhone 12`（Identifier: `00008101-000D34561EE3003A`，iOS 26.6 / Build 23G71）；
- **实装应用 1（AltStore 官方商店）**：
  - Bundle ID：`com.TXA6HP98BX.com.rileytestut.AltStore`
  - 版本：`2.2.1`
  - 签名状态：🟢 `iPhone Developer: zwmfree@qq.com (K7NZ5F3428)`
- **实装应用 2（相册 客户端）**：
  - Bundle ID：`com.zwm.album.TXA6HP98BX`
  - 版本：`0.6.45`
  - 签名状态：🟢 `iPhone Developer: zwmfree@qq.com (K7NZ5F3428)`
- **验证方式**：通过底层真机 `pymobiledevice3 apps list` 完整读取应用清单与签名回执，双应用均已成功写入并激活。

---

## 核心安装分流规范

| 安装目标 | 负责工具 | 执行流程 | 适用场景 |
| :--- | :---: | :--- | :--- |
| **相册 客户端**<br>(`album-iOS-*.ipa`) | **Sideloadly** | Session 1 穿透拉起 ➔ 自动挂载桌面最新 IPA ➔ 自动触发 Start ➔ 用户输入密码 | 相册 App 升级、直接恢复相册功能 |
| **AltStore 自身**<br>(手机端应用商店) | **AltServer** | 托盘图标 ➔ `Install AltStore` ➔ 选择 `z的 iPhone` ➔ 用户输入密码 | 重新激活手机端 AltStore，支持局域网无线续签 |

---

## AI 自动化调度与代劳优先铁律（Session 1 穿透）

> [!IMPORTANT]
> **最高减负原则**：严禁 AI 要求用户手动去逐层找文件、拖拽、点按钮或排查配置。AI 必须利用系统级能力完成前置 99% 的操作，用户全程只保留唯一的“输入密码”动作。

### 1. 为什么不能直接后台静默拉起图形窗口？
- AI 运行环境位于 Windows **Session 0 后台服务会话**。直接调用 `subprocess.Popen` 或 `Start-Process` 会把窗口创建在后台隔离沙箱中，导致用户在物理显示器（Session 1）上**看不到窗口**。

### 2. 标准 Session 1 穿透拉起 SOP：
通过 Windows 计划任务的交互令牌（`/it`）穿透到用户当前前台桌面：
```powershell
# 1. 创建交互式穿透任务
schtasks /create /tn "SideloadlyInteractive" /tr "pythonw.exe D:\AICode\run_interactive_sideloadly.py" /sc once /st 00:00 /it /f

# 2. 立即触发执行
schtasks /run /tn "SideloadlyInteractive"

# 3. 执行完毕后立即清理任务
schtasks /delete /tn "SideloadlyInteractive" /f
```

### 3. 自动化流转标准：
1. **自动前置检查**：检测 USB 连接、Apple Mobile Device 驱动与目标 IPA 文件；
2. **穿透拉起与挂载**：在 Session 1 桌面拉起 Sideloadly 并自动加载 IPA；
3. **自动聚焦与点击**：置顶主窗口并模拟点击【Start】按钮；
4. **用户唯一交互**：此时屏幕直接弹出 Apple 密码框，用户仅输入密码并回车；
5. **后续自动收口**：Sideloadly 自动完成 Anisette、向苹果申请证书、注入、签名与安装至 100%。

---

## 苹果端相册体验升级规划（v0.7.0+ 对齐 Android）

针对 Android 端已打磨成熟的流畅发布体验，iOS 相册端（`tools/device-share-hub/ios/Album/`）必须全面对齐以下 4 项核心能力：

1. **直接一键发布大按钮**：
   - 选中图片后，底部呈现高亮主操作条；
   - 点击时**自动将文案写入系统剪贴板 ➔ 自动调起 iOS 原生分享菜单**（小红书/抖音），一步到位；
2. **文案卡片点击复制**：
   - 顶部独立呈现绿色圆角卡片，单点即可复制文案并弹出轻量 Toast 提示；
3. **分享计数与防重防护**：
   - 记录作品分享次数，二次发布前弹窗防重确认；
4. **全套视觉风格对齐**：
   - 采用与 Android 完全一致的 246 暖白质感底色 + 18px 自然圆角卡片。

---

## 常见排障与第一性原理

1. **`No code signature found (0xe800801c)`**：
   - 原因：直接用原生工具推送了 GitHub CI 产出的原始 IPA（未包含个人证书）；
   - 解法：必须通过 Sideloadly 或 AltServer 借助 Apple ID 完成本地 Resign 签名注入。
2. **掉签原因与周期**：
   - 苹果个人免费开发者账号生成的描述文件严格限制有效期为 **7 天**；
   - 保持电脑端 `sideloadlydaemon` 或 `AltServer` 运行，手机和电脑连在同一 Wi-Fi 下可实现后台自动续签。
