#requires -Version 5.1
[CmdletBinding()]
param(
    [int]$Port = 45835
)

$ErrorActionPreference = 'Stop'
# 调用既有启停脚本（DSH-110 直接复用，不另起一套）
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$StartScript = Join-Path $ScriptDir "start_online_gallery_service.ps1"

if (-not (Test-Path -LiteralPath $StartScript)) {
    Write-Host "未找到启动脚本：$StartScript" -ForegroundColor Red
    exit 1
}

# 简单包装：调 start_online_gallery_service.ps1，传 -Restart 保证幂等
# -Restart：先 kill 掉任何仍在监听的旧进程，再拉新进程。
# 为什么不能省：旧进程会继续用旧代码应答（历史上 thumb=1 静默回落成发原图，
# 手机端在线回收站直接卡死）。开机自启必须保证加载的是磁盘上的最新代码。
& $StartScript -Port $Port -Restart
$svcExit = $LASTEXITCODE

# DSH-191：顺带拉起局域网极速更新源（4348）。
# 它和主服务是同一台机器上的两个常驻进程，历史上只有主服务被挂进开机自启，
# 4348 从来没被拉起过 —— 手机设置页显示着「更新源 电脑局域网 :4348」，实际没人应答。
# 这里挂上，就能随既有开机自启一起恢复。失败不影响主服务，只告警。
$LanScript = Join-Path $ScriptDir "start_lan_update_server.ps1"
if (Test-Path -LiteralPath $LanScript) {
    try {
        & $LanScript -Port 4348 -Restart | Out-Host
    } catch {
        Write-Host "[warn] LAN update server (4348) 启动失败：$($_.Exception.Message)" -ForegroundColor Yellow
    }
} else {
    Write-Host "[warn] 未找到 $LanScript，跳过 4348 更新源" -ForegroundColor Yellow
}

exit $svcExit