#requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.zwm.gallery'
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

function Get-RemoteSha256 {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$RemotePath
    )

    $lines = @(& $Adb -s $Serial shell sha256sum $RemotePath 2>$null)
    if ($LASTEXITCODE -ne 0) { return $null }
    foreach ($line in $lines) {
        $match = [regex]::Match([string]$line, '(?i)\b[0-9a-f]{64}\b')
        if ($match.Success) { return $match.Value.ToLowerInvariant() }
    }
    return $null
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

Write-Host '相册 USB 一键复制' -ForegroundColor Cyan
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
Write-Host "本地文件：$($apk.Name)"
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

$checks = @()
foreach ($device in $authorized) {
    $installed = Get-InstalledVersion -Adb $adb -Serial $device.Serial
    $checks += [pscustomobject]@{
        Device = $device
        Installed = $installed
    }
}

Write-Host 'USB 手机状态：'
foreach ($check in $checks) {
    $installedText = if ($check.Installed.Installed) {
        "$($check.Installed.VersionName) / code $($check.Installed.VersionCode)"
    } else { '未安装相册' }
    Write-Host "  - $(Format-DeviceLabel $check.Device)：$installedText"
}

$selected = $null
if ($checks.Count -eq 1) {
    $selected = $checks[0]
} else {
    Write-Host '发现多台 USB 手机，请输入编号；不会默认群发。' -ForegroundColor Yellow
    for ($index = 0; $index -lt $checks.Count; $index++) {
        Write-Host "  [$($index + 1)] $(Format-DeviceLabel $checks[$index].Device)"
    }
    $choice = Read-Host '选择编号'
    $choiceNumber = 0
    if ((-not [int]::TryParse($choice, [ref]$choiceNumber)) -or $choiceNumber -lt 1 -or $choiceNumber -gt $checks.Count) {
        Stop-WithMessage '编号无效，已取消复制。'
    }
    $selected = $checks[$choiceNumber - 1]
}

Write-Host "目标：$(Format-DeviceLabel $selected.Device)"
$remoteDirectory = '/sdcard/Download'
$remoteName = "album-Android-v$($apk.VersionName).apk"
$remotePath = "$remoteDirectory/$remoteName"
$remoteTempPath = "$remotePath.incoming"
Write-Host "手机目标：$remotePath"
if ($CheckOnly) {
    Write-Host '检查模式：未执行复制。' -ForegroundColor Yellow
    exit 0
}

Write-Host '正在准备手机 Download 文件夹…'
& $adb -s $selected.Device.Serial shell mkdir -p $remoteDirectory *> $null
if ($LASTEXITCODE -ne 0) {
    Stop-WithMessage '无法访问手机的 Download 文件夹。请确认手机已解锁并允许 USB 调试。'
}

$remoteHash = Get-RemoteSha256 -Adb $adb -Serial $selected.Device.Serial -RemotePath $remotePath
if ($remoteHash -eq $hash) {
    Write-Host '手机已有相同文件，跳过复制，避免重复传输。' -ForegroundColor Green
    Write-Host "文件位置：$remotePath"
    Write-Host '未安装应用，未修改应用版本和数据。'
    exit 0
}

Write-Host '正在复制到手机 Download 文件夹…'
& $adb -s $selected.Device.Serial shell rm -f $remoteTempPath *> $null
$pushOutput = @(& $adb -s $selected.Device.Serial push $apk.Path $remoteTempPath 2>&1)
$pushExitCode = $LASTEXITCODE
foreach ($line in $pushOutput) { Write-Host ([string]$line) }
if ($pushExitCode -ne 0) {
    Stop-WithMessage '复制失败。临时文件不会被当作完整安装包使用。'
}

$tempHash = Get-RemoteSha256 -Adb $adb -Serial $selected.Device.Serial -RemotePath $remoteTempPath
if ($tempHash -ne $hash) {
    & $adb -s $selected.Device.Serial shell rm -f $remoteTempPath *> $null
    Stop-WithMessage '复制后校验失败，已删除手机上的临时文件，没有留下不完整文件。'
}

& $adb -s $selected.Device.Serial shell mv -f $remoteTempPath $remotePath *> $null
if ($LASTEXITCODE -ne 0) {
    & $adb -s $selected.Device.Serial shell rm -f $remoteTempPath *> $null
    Stop-WithMessage '复制完成但改名失败，已清理临时文件。'
}

$finalHash = Get-RemoteSha256 -Adb $adb -Serial $selected.Device.Serial -RemotePath $remotePath
if ($finalHash -ne $hash) {
    Stop-WithMessage '手机最终文件校验失败；没有继续重试，请先检查数据线和手机存储。'
}

Write-Host "复制完成：$remotePath" -ForegroundColor Green
Write-Host "本机 SHA-256：$hash"
Write-Host "手机 SHA-256：$finalHash"
Write-Host '未安装应用，未修改应用版本和数据。'

