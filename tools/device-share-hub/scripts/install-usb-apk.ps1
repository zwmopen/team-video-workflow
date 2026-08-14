#requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.zwm.gallery'
$packageDisplayName = '相册'
$cacheRoot = Join-Path $env:LOCALAPPDATA 'ZwmDeviceShareHub\mobile-updates'
$projectApkRoot = 'D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\android\out'

function Stop-WithMessage {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [int]$ExitCode = 1
    )
    Write-Host "失败：$Message" -ForegroundColor Red
    exit $ExitCode
}

function Find-Adb {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $sdkRoots = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique

    foreach ($sdkRoot in $sdkRoots) {
        $candidate = Join-Path $sdkRoot 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

function Get-UsbDevices {
    param([Parameter(Mandatory = $true)][string]$Adb)

    & $Adb start-server *> $null
    if ($LASTEXITCODE -ne 0) {
        Stop-WithMessage 'ADB 服务启动失败。请确认 Android SDK platform-tools 可用。'
    }

    $lines = @(& $Adb devices -l 2>&1)
    if ($LASTEXITCODE -ne 0) {
        Stop-WithMessage '读取 USB 手机列表失败。'
    }

    $devices = @()
    foreach ($line in $lines) {
        $text = [string]$line
        $match = [regex]::Match($text, '^\s*(\S+)\s+(device|unauthorized|offline)(?:\s+(.*))?$')
        if (-not $match.Success) { continue }

        $details = $match.Groups[3].Value
        # adb devices -l marks physical USB transports with usb:<bus>-<port>.
        # Wi-Fi ADB entries normally use an IP:port serial and do not pass this filter.
        $isUsb = $details -match '(?:^|\s)usb:\S+'
        if (-not $isUsb) { continue }

        $model = ''
        $modelMatch = [regex]::Match($details, '(?:^|\s)model:(\S+)')
        if ($modelMatch.Success) { $model = $modelMatch.Groups[1].Value }

        $devices += [pscustomobject]@{
            Serial = $match.Groups[1].Value
            State = $match.Groups[2].Value
            Details = $details
            Model = $model
        }
    }
    return $devices
}

function Get-InstalledVersion {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial
    )

    $lines = @(& $Adb -s $Serial shell dumpsys package $packageName 2>$null)
    $versionName = ''
    $versionCode = -1
    foreach ($line in $lines) {
        $text = [string]$line
        if ($text -match 'versionName=([^\s]+)') { $versionName = $Matches[1] }
        if ($text -match 'versionCode=(\d+)') { $versionCode = [int]$Matches[1] }
    }
    return [pscustomobject]@{
        Installed = -not [string]::IsNullOrWhiteSpace($versionName)
        VersionName = $versionName
        VersionCode = $versionCode
    }
}

function Get-VersionValue {
    param([Parameter(Mandatory = $true)][string]$Value)
    try { return [version]$Value }
    catch { return [version]'0.0.0.0' }
}

function Find-LatestApk {
    $roots = @($cacheRoot, $projectApkRoot) | Select-Object -Unique
    $candidates = @()
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        foreach ($file in (Get-ChildItem -LiteralPath $root -Filter '*.apk' -File -ErrorAction SilentlyContinue)) {
            if ($file.Length -lt 64KB) { continue }
            $match = [regex]::Match($file.Name, '(?i)(\d+\.\d+(?:\.\d+)*)')
            if (-not $match.Success) { continue }
            $candidates += [pscustomobject]@{
                Path = $file.FullName
                Name = $file.Name
                VersionName = $match.Groups[1].Value
                Version = Get-VersionValue $match.Groups[1].Value
                Length = $file.Length
                LastWriteTime = $file.LastWriteTime
            }
        }
    }
    if ($candidates.Count -eq 0) { return $null }
    return $candidates |
        Sort-Object Version, LastWriteTime -Descending |
        Select-Object -First 1
}

function Format-DeviceLabel {
    param([Parameter(Mandatory = $true)]$Device)
    $model = if ([string]::IsNullOrWhiteSpace($Device.Model)) { 'Android 手机' } else { $Device.Model }
    return "$model [$($Device.Serial)]"
}

Write-Host '相册 USB 一键安装' -ForegroundColor Cyan
Write-Host '正在寻找本地最新 APK…'

$adb = Find-Adb
if (-not $adb) {
    Stop-WithMessage '没有找到 adb.exe。请安装 Android SDK platform-tools，或把 adb 加入 PATH。'
}

$apk = Find-LatestApk
if (-not $apk) {
    Stop-WithMessage "没有找到可用 APK。请先把安装包放入 $cacheRoot。"
}

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk.Path).Hash.ToLowerInvariant()
Write-Host "安装包：$($apk.Name)"
Write-Host "版本：$($apk.VersionName)；大小：$([math]::Round($apk.Length / 1MB, 2)) MB"
Write-Host "SHA-256：$hash"

$devices = @(Get-UsbDevices -Adb $adb)
if ($devices.Count -eq 0) {
    Stop-WithMessage '没有发现已授权的 USB Android 手机。请插好数据线，并在手机上点击“允许 USB 调试”。'
}

$unauthorized = @($devices | Where-Object { $_.State -ne 'device' })
if ($unauthorized.Count -gt 0) {
    Write-Host '发现未授权或离线的 USB 设备：' -ForegroundColor Yellow
    foreach ($device in $unauthorized) { Write-Host "  - $(Format-DeviceLabel $device)：$($device.State)" }
}

$authorized = @($devices | Where-Object { $_.State -eq 'device' })
if ($authorized.Count -eq 0) {
    Stop-WithMessage '没有已授权且在线的 USB 手机。请解锁手机并允许 USB 调试。'
}

$targetVersion = Get-VersionValue $apk.VersionName
$checks = @()
foreach ($device in $authorized) {
    $installed = Get-InstalledVersion -Adb $adb -Serial $device.Serial
    $needsUpdate = -not $installed.Installed
    if ($installed.Installed) {
        $needsUpdate = (Get-VersionValue $installed.VersionName) -lt $targetVersion
    }
    $checks += [pscustomobject]@{
        Device = $device
        Installed = $installed
        NeedsUpdate = $needsUpdate
    }
}

Write-Host 'USB 手机状态：'
foreach ($check in $checks) {
    $installedText = if ($check.Installed.Installed) {
        "$($check.Installed.VersionName) / code $($check.Installed.VersionCode)"
    } else { '未安装相册' }
    $actionText = if ($check.NeedsUpdate) { '需要安装' } else { '已是同版或更新版，跳过' }
    Write-Host "  - $(Format-DeviceLabel $check.Device)：$installedText → $actionText"
}

$eligible = @($checks | Where-Object { $_.NeedsUpdate })
if ($eligible.Count -eq 0) {
    Write-Host '没有需要更新的 USB 手机，本次没有执行安装。' -ForegroundColor Green
    exit 0
}

$selected = $null
if ($eligible.Count -eq 1) {
    $selected = $eligible[0]
} else {
    Write-Host '有多台 USB 手机需要更新，请输入编号；不会默认群发。' -ForegroundColor Yellow
    for ($index = 0; $index -lt $eligible.Count; $index++) {
        Write-Host "  [$($index + 1)] $(Format-DeviceLabel $eligible[$index].Device)"
    }
    $choice = Read-Host '选择编号'
    $choiceNumber = 0
    if ((-not [int]::TryParse($choice, [ref]$choiceNumber)) -or $choiceNumber -lt 1 -or $choiceNumber -gt $eligible.Count) {
        Stop-WithMessage '编号无效，已取消安装。'
    }
    $selected = $eligible[$choiceNumber - 1]
}

Write-Host "目标：$(Format-DeviceLabel $selected.Device)"
if ($CheckOnly) {
    Write-Host '检查模式：未执行安装。' -ForegroundColor Yellow
    exit 0
}

Write-Host '正在通过 USB 安装，手机屏幕保持解锁…'
$installOutput = @(& $adb -s $selected.Device.Serial install -r $apk.Path 2>&1)
$installExitCode = $LASTEXITCODE
foreach ($line in $installOutput) { Write-Host ([string]$line) }
if ($installExitCode -ne 0) {
    Stop-WithMessage 'ADB 安装失败。常见原因是手机未授权、签名不一致，或手机上的应用版本更高。'
}

$after = Get-InstalledVersion -Adb $adb -Serial $selected.Device.Serial
if (-not $after.Installed -or (Get-VersionValue $after.VersionName) -lt $targetVersion) {
    Stop-WithMessage 'ADB 返回安装成功，但重新读取手机版本未达到目标；已停止，不重复重试。'
}

Write-Host "安装完成：$($after.VersionName) / code $($after.VersionCode)" -ForegroundColor Green
Write-Host '手机原有作品和应用数据未由脚本清除。'

