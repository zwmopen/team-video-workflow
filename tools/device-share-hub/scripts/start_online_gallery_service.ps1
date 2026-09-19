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
        Write-Host "Restarting existing online gallery service..."
        foreach ($c in $conns) {
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 800
    } else {
        try {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/online/status" -TimeoutSec 2
            Write-Host "Online gallery service is already running." -ForegroundColor Green
            Write-Host "LAN Address: http://$($resp.ip):$Port"
            Write-Host "Total Works: $($resp.totalWorks)"
            return
        } catch {
            Write-Host "Port occupied but not responding, cleaning up..."
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

Write-Host "Starting Online Gallery LAN Service (Port: $Port)..."
$cmdLine = "`"$pythonExe`" `"$ServiceScript`" --port $Port"
$wmi = [wmiclass]"Win32_Process"
$res = $wmi.Create($cmdLine, $ScriptDir, $null)
if ($res.ReturnValue -ne 0) {
    Start-Process -FilePath $pythonExe -ArgumentList "`"$ServiceScript`" --port $Port" -WorkingDirectory $ScriptDir -WindowStyle Hidden
}

# 3. Wait for service readiness
$ready = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Milliseconds 300
    try {
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/online/status" -TimeoutSec 1
        if ($resp.ok) {
            $ready = $true
            Write-Host "Online Gallery LAN Service started successfully!" -ForegroundColor Green
            Write-Host "LAN Address: http://$($resp.ip):$Port" -ForegroundColor Cyan
            Write-Host "Total Works: $($resp.totalWorks)"
            break
        }
    } catch {
        # ignore retry
    }
}

if (-not $ready) {
    throw "Service failed to respond within 5 seconds on port $Port."
}
