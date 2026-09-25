#requires -Version 5.1
[CmdletBinding()]
param(
    [int]$Port = 45835,
    [switch]$Json
)

$ErrorActionPreference = 'SilentlyContinue'

$uri = "http://127.0.0.1:$Port/api/online/status"
try {
    $resp = Invoke-RestMethod -Uri $uri -TimeoutSec 3
} catch {
    $status = "DOWN"
    $msg = $_.Exception.Message
}

if (-not $resp) {
    if ($Json) {
        [PSCustomObject]@{
            status = "DOWN"
            error = $msg
        } | ConvertTo-Json -Compress
    } else {
        Write-Host "❌ 在线相册服务未运行（端口 $Port 无响应）" -ForegroundColor Red
        Write-Host "   原因：$msg"
        Write-Host "   启动方法：双击桌面「📷 在线相册」快捷方式，或运行 scripts\start_online_gallery_service.ps1" -ForegroundColor Yellow
    }
    exit 1
}

# 解析 watchdog 状态
$wd = $resp.watchdog
$wdStatus = if ($wd.active) { "🟢 实时监听" } else { "🟡 仅手动刷新" }
$lastChange = "从未"
if ($wd.lastChangeAt -gt 0) {
    $lastChange = (Get-Date -UnixTimeSeconds $wd.lastChangeAt).ToString("HH:mm:ss")
}
$lastPoll = "从未"
if ($wd.lastPollAt -gt 0) {
    $lastPoll = (Get-Date -UnixTimeSeconds $wd.lastPollAt).ToString("HH:mm:ss")
}

if ($Json) {
    [PSCustomObject]@{
        status = "UP"
        ip = $resp.ip
        port = $resp.port
        totalWorks = $resp.totalWorks
        libraryRoot = $resp.libraryRoot
        watchdogActive = $wd.active
        lastPoll = $lastPoll
        lastChange = $lastChange
    } | ConvertTo-Json -Compress
} else {
    Write-Host "🟢 在线相册服务运行中" -ForegroundColor Green
    Write-Host "   局域网地址：http://$($resp.ip):$Port"
    Write-Host "   总作品数：$($resp.totalWorks) 套"
    Write-Host "   真源目录：$($resp.libraryRoot)"
    Write-Host "   文件监听：$wdStatus（每 $($wd.intervalSec) 秒轮询一次）"
    Write-Host "   上次轮询：$lastPoll / 上次变更：$lastChange"
}
exit 0