#requires -Version 5.1
<#
DSH-110：注册/卸载「在线相册服务」Windows 开机自启（启动文件夹方式）。

为什么选「启动文件夹」而不是 NSSM 注册 Windows 服务？
- NSSM 是第三方工具，需要下载并放 PATH 才能用；启动文件夹是 Windows 自带机制，零依赖。
- 服务端本身就是用户态 pythonw.exe 进程，不需要 NT 内核服务权限。
- 启动文件夹的 .lnk 开机后自动执行，与任务计划程序同等级别，但零配置。

用法：
  install-autostart.ps1          # 注册开机自启
  install-autostart.ps1 -Uninstall  # 卸载
#>

[CmdletBinding()]
param(
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$RestartScript = Join-Path $ScriptDir "restart_online_gallery.ps1"

if (-not (Test-Path -LiteralPath $RestartScript)) {
    Write-Host "未找到 restart 脚本：$RestartScript" -ForegroundColor Red
    exit 1
}

# 启动文件夹路径（Windows shell:startup 的真实位置）
$StartupDir = [Environment]::GetFolderPath('Startup')
if (-not $StartupDir) {
    Write-Host "无法定位启动文件夹" -ForegroundColor Red
    exit 1
}

$LinkName = "DSH-OnlineGallery-AutoStart.lnk"
$LinkPath = Join-Path $StartupDir $LinkName

# DSH-112 fix2：历史遗留入口 —— 2026-09-20 那版用的是 .vbs（直连 pythonw / 后改为 -Restart）。
# 它和本脚本装的 .lnk 是同一个服务的两份开机自启，开机瞬间会并发抢 45835 端口；
# 更要命的是 -Uninstall 只删 .lnk，卸载后 .vbs 照样把服务拉起来 —— 用户以为卸了其实没卸。
# 所以注册/卸载两条路径都顺手清掉它（幂等，不存在就跳过）。
$LegacyVbsPath = Join-Path $StartupDir "DeviceShareHub-OnlineGallery.vbs"

if ($Uninstall) {
    if (Test-Path -LiteralPath $LinkPath) {
        Remove-Item -LiteralPath $LinkPath -Force
        if (Test-Path -LiteralPath $LegacyVbsPath) {
            Remove-Item -LiteralPath $LegacyVbsPath -Force
            Write-Host "   (also removed legacy entry $LegacyVbsPath)" -ForegroundColor DarkGray
        }
        Write-Host "✅ 已卸载开机自启（删除 $LinkPath）" -ForegroundColor Green
    } else {
        Write-Host "未找到开机自启项（$LinkPath 不存在），无需卸载" -ForegroundColor Yellow
    }
    exit 0
}

# 创建 .lnk（PowerShell 创建快捷方式的标准做法）
$WshShell = New-Object -ComObject WScript.Shell
$Shortcut = $WshShell.CreateShortcut($LinkPath)
$Shortcut.TargetPath = (Get-Command powershell.exe).Source
$Shortcut.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$RestartScript`""
$Shortcut.WorkingDirectory = $ScriptDir
$Shortcut.WindowStyle = 7  # 7 = 最小化（避免黑框闪现）
$Shortcut.Description = "DSH 在线相册服务开机自启（DSH-110）"
$Shortcut.Save()
# 释放 COM 对象（避免进程残留）
[System.Runtime.Interopservices.Marshal]::ReleaseComObject($Shortcut) | Out-Null
[System.Runtime.Interopservices.Marshal]::ReleaseComObject($WshShell) | Out-Null

# 清掉历史遗留的 .vbs 入口，保证全局只有一份开机自启（见上面 DSH-112 fix2 说明）
if (Test-Path -LiteralPath $LegacyVbsPath) {
    Remove-Item -LiteralPath $LegacyVbsPath -Force
    Write-Host "[cleanup] removed legacy autostart entry: $LegacyVbsPath" -ForegroundColor DarkGray
}

Write-Host "✅ 已注册开机自启：" -ForegroundColor Green
Write-Host "   $LinkPath"
Write-Host "   下次开机/重启后会自动启动在线相册服务"
Write-Host "   立即启动：双击桌面「📷 在线相册」快捷方式"
Write-Host "   卸载：powershell -File install-autostart.ps1 -Uninstall"