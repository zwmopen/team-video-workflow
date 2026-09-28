#requires -Version 5.1
<#
.SYNOPSIS
  Online Gallery LAN Service Supervisor - Windows Triple-Guard Auto-Start Installer
  1. Startup Folder VBS: shell:startup\OnlineGallery-Supervisor.vbs
  2. Registry Run Key: HKCU\Software\Microsoft\Windows\CurrentVersion\Run\OnlineGallery-Supervisor
  3. Windows Scheduled Task: OnlineGallery-Supervisor (Logon trigger, 1-min retry on failure, permanent)
#>

[CmdletBinding()]
param(
    [switch]$Uninstall,
    [switch]$StartImmediately
)

$ErrorActionPreference = 'Continue'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$SupervisorPy = Join-Path $ScriptDir "online_gallery_supervisor.py"
$SupervisorVbs = Join-Path $ScriptDir "OnlineGallery-Supervisor.vbs"
$TaskName = "OnlineGallery-Supervisor"

# Locate pythonw.exe
$defaultPyw = "C:\Users\z\AppData\Local\Programs\Python\Python311\pythonw.exe"
if (Test-Path -LiteralPath $defaultPyw) {
    $pythonwExe = $defaultPyw
} else {
    $foundPyw = Get-Command pythonw.exe -ErrorAction SilentlyContinue
    if ($foundPyw) {
        $pythonwExe = $foundPyw.Source
    } else {
        $pythonwExe = "pythonw.exe"
    }
}

# Uninstall branch
if ($Uninstall) {
    Write-Host ">>> Unregistering Online Gallery Supervisor..." -ForegroundColor Yellow
    try {
        $service = New-Object -ComObject("Schedule.Service")
        $service.Connect()
        $root = $service.GetFolder("\")
        $root.DeleteTask($TaskName, 0)
        Write-Host "[OK] Removed Windows Scheduled Task: $TaskName" -ForegroundColor Green
    } catch {}

    try {
        Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name $TaskName -ErrorAction SilentlyContinue
        Write-Host "[OK] Removed Registry Run Key: $TaskName" -ForegroundColor Green
    } catch {}

    $startupFolder = [System.Environment]::GetFolderPath('Startup')
    $targetVbs = Join-Path $startupFolder "OnlineGallery-Supervisor.vbs"
    if (Test-Path -LiteralPath $targetVbs) {
        Remove-Item -LiteralPath $targetVbs -Force -ErrorAction SilentlyContinue
        Write-Host "[OK] Removed Startup Folder VBS: $targetVbs" -ForegroundColor Green
    }
    Write-Host "[OK] Online Gallery Supervisor autostart entries fully uninstalled." -ForegroundColor Green
    exit 0
}

Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host " [AI Station Hub] Hardening Online Gallery Service Windows Auto-Start..." -ForegroundColor Cyan
Write-Host "==========================================================================" -ForegroundColor Cyan

# 1. Startup Folder VBS
$startupFolder = [System.Environment]::GetFolderPath('Startup')
$startupVbsPath = Join-Path $startupFolder "OnlineGallery-Supervisor.vbs"
$vbsContent = "Set WshShell = CreateObject(`"WScript.Shell`")`r`nWshShell.Run `"`"`"$pythonwExe`"`" `"`"$SupervisorPy`"`"`", 0, False`r`n"

[System.IO.File]::WriteAllText($startupVbsPath, $vbsContent, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText($SupervisorVbs, $vbsContent, [System.Text.Encoding]::ASCII)
Write-Host "[1/3] Startup Folder VBS Guard Ready: $startupVbsPath" -ForegroundColor Green

# 2. Registry Run Key
$runCmd = "wscript.exe `"$SupervisorVbs`""
Set-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name $TaskName -Value $runCmd -Force
Write-Host "[2/3] Registry Run Key Guard Ready: HKCU\...\Run\$TaskName -> $runCmd" -ForegroundColor Green

# 3. Windows Scheduled Task via Schedule.Service COM
try {
    $service = New-Object -ComObject("Schedule.Service")
    $service.Connect()
    $root = $service.GetFolder("\")
    $taskDef = $service.NewTask(0)
    $taskDef.RegistrationInfo.Description = "Online Gallery Service High-Availability Supervisor"
    $taskDef.RegistrationInfo.Author = $env:USERNAME

    $settings = $taskDef.Settings
    $settings.Enabled = $true
    $settings.StartWhenAvailable = $true
    $settings.Hidden = $false
    $settings.RestartCount = 999
    $settings.RestartInterval = "PT1M"
    $settings.ExecutionTimeLimit = "PT0S"
    $settings.DisallowStartIfOnBatteries = $false
    $settings.StopIfGoingOnBatteries = $false

    # Trigger: AtLogon (9)
    $trigger = $taskDef.Triggers.Create(9)
    $trigger.UserId = $env:USERNAME
    $trigger.Enabled = $true

    # Action: Exec (0)
    $action = $taskDef.Actions.Create(0)
    $action.Path = $pythonwExe
    $action.Arguments = "`"$SupervisorPy`""
    $action.WorkingDirectory = $ScriptDir

    # TASK_CREATE_OR_UPDATE = 6, TASK_LOGON_INTERACTIVE_TOKEN = 3
    $root.RegisterTaskDefinition($TaskName, $taskDef, 6, $null, $null, 3) | Out-Null
    Write-Host "[3/3] Windows Scheduled Task Guard Ready: $TaskName (AtLogon / 1-min retry / Permanent / 0 Window)" -ForegroundColor Green
} catch {
    Write-Warning "Scheduled task registration error: $($_.Exception.Message)"
}

# Optional immediate activation
if ($StartImmediately) {
    Write-Host ">>> Activating Online Gallery Supervisor immediately..." -ForegroundColor Yellow
    try {
        $task = $root.GetTask($TaskName)
        $task.Run($null)
        Write-Host "[OK] Started via Scheduled Task." -ForegroundColor Green
    } catch {
        Start-Process -FilePath "wscript.exe" -ArgumentList "`"$SupervisorVbs`"" -WindowStyle Hidden
        Write-Host "[OK] Started via WScript VBS." -ForegroundColor Green
    }
}

Write-Host "`nOnline Gallery Service Triple Guard Auto-Start registration completed successfully!`n" -ForegroundColor Cyan
