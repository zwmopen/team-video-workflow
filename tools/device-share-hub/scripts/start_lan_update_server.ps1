#requires -Version 5.1
<#
DSH-191：拉起 / 重启「局域网极速更新源」(4348)。

为什么需要这个脚本：
  docs/MAINTAINER_HANDOFF.md 从 2026-09-12 起就把 lan_update_server.py 写成「常驻服务」，
  但全仓库没有任何地方启动它 —— 于是手机设置页显示「更新源 电脑局域网 (x.x.x.x:4348)」，
  点检查更新永远走的是降级路径（1.5 秒超时 -> 云端源），甚至拿到 0.8.109 这种陈年老包。
  本脚本补上这个缺口，并挂到 restart_online_gallery.ps1 上，随既有开机自启一起起来。

用法：
  start_lan_update_server.ps1            # 已在跑就跳过（幂等），没跑就拉起
  start_lan_update_server.ps1 -Restart   # 先杀旧进程再拉新进程（改了代码必须用这个）
#>

[CmdletBinding()]
param(
    [int]$Port = 4348,
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
# 注意：用 $PSScriptRoot 而不是 $MyInvocation.MyCommand.Definition。
# 后者在被别的脚本以 `& "路径"` 方式调用时会是空值，Join-Path 直接抛「LiteralPath 为空」。
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Definition }
if (-not $ScriptDir) { $ScriptDir = "D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\scripts" }
$ServiceScript = Join-Path $ScriptDir "lan_update_server.py"

if (-not (Test-Path -LiteralPath $ServiceScript)) {
    Write-Host "未找到服务脚本：$ServiceScript" -ForegroundColor Red
    exit 1
}

# 1. 端口占用检查
$conns = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($conns.Count -gt 0) {
    if (-not $Restart) {
        try {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
            Write-Host "LAN update server already running: v$($resp.latest_version) (port $Port)" -ForegroundColor Green
            exit 0
        } catch {
            Write-Host "Port $Port occupied but not responding, cleaning up..." -ForegroundColor Yellow
            foreach ($c in $conns) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
            Start-Sleep -Milliseconds 800
        }
    } else {
        Write-Host "Restarting LAN update server on port $Port ..."
        foreach ($c in $conns) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Milliseconds 800
    }
}

# 2. 找 pythonw.exe（沿用 start_online_gallery_service.ps1 的既有约定，无控制台黑框）
$defaultPyw = "C:\Users\z\AppData\Local\Programs\Python\Python311\pythonw.exe"
if (Test-Path -LiteralPath $defaultPyw) {
    $pythonExe = $defaultPyw
} else {
    $pywCmd = Get-Command pythonw.exe -ErrorAction SilentlyContinue
    if ($pywCmd) {
        $pythonExe = $pywCmd.Source
    } else {
        $pyCmd = Get-Command python.exe -ErrorAction SilentlyContinue
        $pythonExe = if ($pyCmd) { $pyCmd.Source } else { "C:\Users\z\AppData\Local\Programs\Python\Python311\python.exe" }
    }
}

# 3. 用 Win32_Process.Create 脱离父进程启动（Start-Process 的窗口样式在这里不可靠）
$cmdLine = "`"$pythonExe`" `"$ServiceScript`" --port $Port"
$wmi = [wmiclass]"Win32_Process"
$res = $wmi.Create($cmdLine, $ScriptDir, $null)
if ($res.ReturnValue -ne 0) {
    Write-Host "启动失败，Win32_Process.Create 返回 $($res.ReturnValue)" -ForegroundColor Red
    exit 1
}

# 4. 等它真的监听起来再回报（最多 10 秒）
$ok = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    try {
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
        $ok = $true
        break
    } catch { }
}

if ($ok) {
    Write-Host "✅ LAN update server is LIVE on port $Port" -ForegroundColor Green
    Write-Host "   Android: v$($resp.latest_version)  file=$($resp.file_name)"
    Write-Host "   iOS    : v$($resp.ios.version_name) (build $($resp.ios.build))"
    exit 0
}

Write-Host "⚠️ 进程已创建但端口 $Port 未响应，请看 $ScriptDir\lan_update_server.log" -ForegroundColor Yellow
exit 2
