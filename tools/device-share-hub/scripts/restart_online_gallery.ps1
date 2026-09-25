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
exit $LASTEXITCODE