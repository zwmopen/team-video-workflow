#requires -Version 5.1
[CmdletBinding()]
param(
    [int]$Port = 45835,
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ServiceScript = Join-Path $ScriptDir "online_gallery_service.py"

if (-not (Test-Path -LiteralPath $ServiceScript)) {
    throw "未找到服务脚本：$ServiceScript"
}

# 1. 查找现有进程或端口占用
$conns = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($conns.Count -gt 0) {
    if ($Restart) {
        Write-Host "检测到服务正在运行，正在重启..."
        foreach ($c in $conns) {
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 800
    } else {
        try {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/online/status" -TimeoutSec 2
            Write-Host "在线相册服务已在运行中" -ForegroundColor Green
            Write-Host "局域网地址: http://$($resp.ip):$Port"
            Write-Host "作品总数: $($resp.totalWorks) 套"
            return
        } catch {
            Write-Host "端口被占用但未正常响应，正在清理占用进程..."
            foreach ($c in $conns) {
                Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
            }
            Start-Sleep -Milliseconds 800
        }
    }
}

# 2. 寻找 pythonw.exe，彻底无控制台黑框
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

Write-Host "正在无窗静默启动在线相册 PC 服务 (端口: $Port)..."
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $pythonExe
$psi.Arguments = "`"$ServiceScript`" --port $Port"
$psi.WorkingDirectory = $ScriptDir
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden

$proc = [System.Diagnostics.Process]::Start($psi)
if (-not $proc) {
    throw "启动 Python 进程失败。"
}

# 3. 等待服务就绪
$ready = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Milliseconds 300
    try {
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/online/status" -TimeoutSec 1
        if ($resp.ok) {
            $ready = $true
            Write-Host "在线相册 PC 局域网服务已启动成功！" -ForegroundColor Green
            Write-Host "局域网地址: http://$($resp.ip):$Port" -ForegroundColor Cyan
            Write-Host "作品真源: $($resp.libraryRoot)"
            Write-Host "作品总数: $($resp.totalWorks) 套"
            break
        }
    } catch {
        # ignore retry
    }
}

if (-not $ready) {
    throw "服务在 5 秒内未能正常响应，请检查端口 $Port 占用。"
}
