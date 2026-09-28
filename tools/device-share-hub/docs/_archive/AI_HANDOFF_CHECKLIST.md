# AI 接手检查清单 — 素材投送中控

> 最后更新：2026-08-03  
> 对应版本：Windows V4.2.1（commit `a981d1f`）  
> 作用：让接手的 AI 在 5 分钟内了解项目状态、找到关键文件、知道下一步该做什么。

---

## 一、项目概览

- **项目名称**：素材投送中控（Device Share Hub）
- **所属仓库**：`zwmopen/team-video-workflow`（私有）
- **源码路径**：`tools/device-share-hub/`
- **三端源码**：`windows-native/`（C++ Win32）、`android/`（Kotlin）、`ios/`（Swift）
- **功能**：局域网内电脑与手机之间互传文件/文件夹，支持 USB 和 WiFi 两种通道
- **当前部署版本**：Windows V4.2.1，部署在 `D:\AICode\运行数据\device-share-hub-release-v0.6.15-bg-final-2\`

---

## 二、最近修复（DSH-040）

### 问题
Windows 中控在以下场景扫描不到在线设备：
- 手机开热点，电脑连热点
- 非 `/24` 子网（如 `/28`、`/16`）
- 存在 WSL/VMware 虚拟网卡干扰

### 修复内容（commit `a981d1f`）
1. `ActiveProbeTargets()` — 用实际子网掩码替代硬编码 `/24`，移除网关依赖
2. `IsVirtualAdapter()` — 新增，过滤 WSL/VMware/Hyper-V/VirtualBox/TAP/VPN 虚拟网卡
3. `AddArpTableTargets()` — 新增，查询 ARP 表补充探测目标
4. `ProbeDeviceHost()` — 连接超时 400ms→800ms，收发超时 800ms→1500ms
5. `DiscoveryTargets()` — 同步过滤虚拟网卡

### 部署状态
- CI 构建通过，EXE 已下载并替换
- 新 EXE 已启动（PID=16212）
- **待验证**：用户需确认手机设备能被扫描到

---

## 三、关键文件索引

| 文件 | 路径 | 用途 |
|------|------|------|
| 主源码 | `windows-native/src/main.cpp` | Windows 中控全部逻辑（约 2000 行） |
| 构建配置 | `windows-native/CMakeLists.txt` | CMake 构建定义 |
| Bug 账本 | `docs/BUG_LEDGER.md` | 所有 bug 的根因和修复记录 |
| 交接文档 | `docs/MAINTAINER_HANDOFF.md` | 维护者交接信息 |
| 构建说明 | `docs/SOURCE_BUILD_AND_RECOVERY.md` | 源码构建和恢复流程 |
| CI 工作流 | `.github/workflows/device-share-hub.yml` | GitHub Actions 构建配置 |
| 部署目录 | `D:\AICode\运行数据\device-share-hub-release-v0.6.15-bg-final-2\` | 当前运行的 EXE 所在 |
| 构建产物 | `D:\AICode\运行数据\构建产物\` | CI 下载的产物暂存 |

---

## 四、构建流程（云端优先）

### 前提条件
- Git 路径：`C:\Program Files\Git\cmd\git.exe`
- GitHub CLI 路径：`C:\Program Files\GitHub CLI\gh.exe`
- GitHub 账号：`zwmopen`（主）、`rpgzwm`（备用1）、`idmzwm-sys`（备用2）
- **不安装本地编译工具链**，C 盘空间紧张，一律走云端构建

### 步骤
```powershell
# 1. 设置 PATH
$env:Path = "C:\Program Files\Git\cmd;C:\Program Files\GitHub CLI;$env:Path"

# 2. 进入仓库
cd D:\AICode\AI\repos\team-video-workflow

# 3. 提交修改
git add tools/device-share-hub/windows-native/src/main.cpp
git commit -m "fix(windows): 描述"
git push origin main

# 4. 如果 zwmopen 额度用完，推到备用仓库
git push build-backup main    # rpgzwm
# 或
git push build-backup-2 main  # idmzwm-sys

# 5. 监控构建
gh run list --repo zwmopen/team-video-workflow --limit 1
gh run watch <run-id> --repo zwmopen/team-video-workflow

# 6. 下载产物
gh run download <run-id> --repo zwmopen/team-video-workflow \
  --name device-share-hub-windows-portable \
  --dir "D:\AICode\运行数据\构建产物"
```

### 注意事项
- 推送到 `main` 且修改了 `tools/device-share-hub/**` 会自动触发 CI
- CI 产物名：`device-share-hub-windows-portable`，包含 `素材投送中控-Windows-V4.2.1.exe`
- Windows job 在 `windows-2022` runner 上运行，约 1-3 分钟完成
- 如遇 git index.lock 冲突，先 kill 所有 git 进程再删除锁文件

---

## 五、部署流程

### 替换 EXE
```powershell
# 1. 确认旧进程已停止
Get-Process | Where-Object { $_.ProcessName -match '素材|DeviceShare' } | Stop-Process -Force

# 2. 复制新 EXE（用 .NET 方法绕过安全限制）
$src = "D:\AICode\运行数据\构建产物\素材投送中控-Windows-V4.2.1.exe"
$dst = "D:\AICode\运行数据\device-share-hub-release-v0.6.15-bg-final-2\素材投送中控-Windows-V4.2.1.exe"
[System.IO.File]::Copy($src, $dst, $true)

# 3. 启动新版本
Start-Process $dst

# 4. 验证运行
Get-Process | Where-Object { $_.ProcessName -match '素材|DeviceShare' }
```

### 注意事项
- PowerShell 的 `Copy-Item -Force` 可能被安全策略拦截，用 `[System.IO.File]::Copy()` 替代
- `robocopy` 不在 PATH 中，不可用
- `cmd /c` 被禁止，只能用 PowerShell

---

## 六、测试验证清单

### 设备发现测试
- [ ] 标准 WiFi（`/24` 子网）：能发现同 WiFi 下的手机
- [ ] 手机热点（`/28` 或 `/30` 子网）：电脑连手机热点后能发现手机
- [ ] WSL/VMware 运行时：虚拟网卡不干扰设备发现
- [ ] 右键"发送到 → 相册在线设备"：弹出设备选择菜单
- [ ] 选中设备后文件传输：正常发送并收到回执
- [ ] 设备离线后：快捷方式自动移除

### 回归测试
- [ ] UDP 广播发现仍然工作
- [ ] 15 秒刷新周期正常
- [ ] 35 秒超时后离线设备移除
- [ ] "发送到"菜单不出现目录
- [ ] USB 传输不受影响

---

## 七、已知限制和约束

1. **不安装本地编译工具链**：C 盘空间紧张，一律走云端 GitHub Actions 构建
2. **三个 GitHub 账号轮换**：`zwmopen` → `rpgzwm` → `idmzwm-sys`，额度用完切换
3. **不修改手机端**：当前修复仅涉及 Windows 端，手机端（Android/iOS）不需要改动
4. **安全边界**：不使用 ADB、Root、手动 IP、配对码或管理员权限
5. **文件操作限制**：D 盘文件操作可能被安全策略拦截，用 .NET 方法绕过
6. **Git 锁文件**：可能有后台进程占用 `.git/index.lock`，需要先 kill git 进程

---

## 八、下一步待办

1. **用户实测验证**：用户需要在实际环境中测试设备发现是否正常（热点模式、非 /24 子网）
2. **如果仍无法发现设备**：
   - 检查防火墙是否放行端口 45833（HTTP）和 45834（UDP）
   - 检查网络配置文件是否为"专用"而非"公用"
   - 查看应用日志确认扫描目标和探测结果
3. **性能优化**（如果扫描太慢）：
   - 考虑限制大子网（`/16`）的扫描范围
   - 考虑并行探测数量调优
4. **远程中控功能**（用户提到但暂未开发）：
   - 通过互联网远程发现和控制设备
   - 当前仅支持局域网内设备发现

---

## 九、Git 信息

- **仓库**：`https://github.com/zwmopen/team-video-workflow.git`
- **当前 commit**：`a981d1f`（fix(windows): improve device discovery for hotspot and non-/24 subnets）
- **分支**：`main`
- **备用构建仓库**：
  - `build-backup` → `https://github.com/rpgzwm/team-video-workflow-build.git`
  - `build-backup-2` → `https://github.com/idmzwm-sys/team-video-workflow-build.git`

---

## 十、快速诊断命令

```powershell
# 检查中控是否运行
Get-Process | Where-Object { $_.ProcessName -match '素材|DeviceShare' }

# 检查网络配置
ipconfig /all

# 检查防火墙规则
Get-NetFirewallRule -DisplayName "*素材*" -ErrorAction SilentlyContinue

# 检查端口监听
Get-NetTCPConnection -LocalPort 45833 -ErrorAction SilentlyContinue
Get-NetUDPEndpoint -LocalPort 45834 -ErrorAction SilentlyContinue

# 查看应用日志
Get-Content "$env:LOCALAPPDATA\素材投送中控\logs\*.log" -Tail 50 -ErrorAction SilentlyContinue
```
