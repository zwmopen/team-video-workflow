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
& $StartScript -Port $Port
exit $LASTEXITCODE