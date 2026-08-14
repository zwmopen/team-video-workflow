#requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$WindowsOnly,
    [switch]$AndroidOnly
)

$ErrorActionPreference = 'Stop'
if ($WindowsOnly -and $AndroidOnly) {
    throw 'WindowsOnly 和 AndroidOnly 不能同时使用。'
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$windowsRoot = Join-Path $projectRoot 'tools\device-share-hub\windows-native'
$androidRoot = Join-Path $projectRoot 'tools\device-share-hub\android'

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "命令失败（$LASTEXITCODE）：$Command $($Arguments -join ' ')"
    }
}

function Find-Gradle941 {
    $command = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $userProfilePath = [Environment]::GetFolderPath('UserProfile')
    $wrapperRoot = Join-Path $userProfilePath '.gradle\wrapper\dists\gradle-9.4.1-bin'
    if (Test-Path -LiteralPath $wrapperRoot) {
        $candidate = Get-ChildItem -LiteralPath $wrapperRoot -Filter gradle.bat -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\gradle-9\.4\.1\\bin\\gradle\.bat$' } |
            Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    return $null
}

function Find-AndroidSdk {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'platforms\android-36')) {
            return $candidate
        }
    }
    return $null
}

function Build-Windows {
    $cmake = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if (-not $cmake) {
        throw '本机缺少 CMake；请使用 GitHub Actions 的 windows-2022 runner 构建 Windows 包。'
    }
    $buildRoot = Join-Path $windowsRoot 'build-local'
    Invoke-Checked $cmake.Source @('-S', $windowsRoot, '-B', $buildRoot, '-G', 'Visual Studio 17 2022', '-A', 'x64')
    Invoke-Checked $cmake.Source @('--build', $buildRoot, '--config', 'Release')
    $ctest = Get-Command ctest.exe -ErrorAction SilentlyContinue
    if (-not $ctest) { throw '本机缺少 CTest；无法完成 Windows 回归验证。' }
    Invoke-Checked $ctest.Source @('--test-dir', $buildRoot, '-C', 'Release', '--output-on-failure')
    Write-Host 'Windows 构建、CTest 已通过。'
}

function Build-Android {
    $sdkRoot = Find-AndroidSdk
    if (-not $sdkRoot) {
        throw '本机缺少 Android SDK 36；请使用 GitHub Actions 的 Android runner 构建 APK。'
    }
    $buildTools = Join-Path $sdkRoot 'build-tools\36.0.0'
    $aapt = Join-Path $buildTools 'aapt.exe'
    if (-not (Test-Path -LiteralPath $aapt)) {
        throw '本机缺少 Android build-tools 36.0.0；请使用 GitHub Actions 构建 APK。'
    }
    $gradle = Find-Gradle941
    if (-not $gradle) {
        throw '本机缺少 Gradle 9.4.1；请使用 GitHub Actions 构建 APK。'
    }

    $oldAndroidSdkRoot = $env:ANDROID_SDK_ROOT
    $oldAndroidHome = $env:ANDROID_HOME
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_HOME = $sdkRoot
    Push-Location $androidRoot
    try {
        Invoke-Checked $gradle @(':app:testDebugUnitTest', ':app:assembleRelease', ':app:lintRelease', '--stacktrace')
    } finally {
        Pop-Location
        $env:ANDROID_SDK_ROOT = $oldAndroidSdkRoot
        $env:ANDROID_HOME = $oldAndroidHome
    }

    $apk = Join-Path $androidRoot 'app\build\outputs\apk\release\app-release.apk'
    $metadataPath = Join-Path $androidRoot 'app\build\outputs\apk\release\output-metadata.json'
    if (-not (Test-Path -LiteralPath $apk) -or -not (Test-Path -LiteralPath $metadataPath)) {
        throw 'Android 构建完成但没有生成 APK 或 output-metadata.json。'
    }
    $permissions = (& $aapt dump permissions $apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'aapt 无法读取 APK 权限。' }
    if ($permissions -notmatch 'android.permission.REQUEST_INSTALL_PACKAGES') {
        throw 'Release APK 缺少 REQUEST_INSTALL_PACKAGES。'
    }
    if ($permissions -notmatch 'android.permission.FOREGROUND_SERVICE_DATA_SYNC') {
        throw 'Release APK 缺少 FOREGROUND_SERVICE_DATA_SYNC。'
    }
    if ($permissions -match "android.permission.INSTALL_PACKAGES") {
        throw 'Release APK 不得声明特权 INSTALL_PACKAGES。'
    }
    $badging = (& $aapt dump badging $apk 2>&1) -join "`n"
    if ($badging -match 'application-debuggable') {
        throw 'Release APK 不得为 debuggable。'
    }

    $metadata = Get-Content -Raw -Encoding UTF8 -LiteralPath $metadataPath | ConvertFrom-Json
    $versionName = [string]$metadata.elements[0].versionName
    $versionCode = [int]$metadata.elements[0].versionCode
    if ([string]::IsNullOrWhiteSpace($versionName) -or $versionCode -lt 1) {
        throw 'Android output metadata 缺少有效版本名或 versionCode。'
    }
    $out = Join-Path $androidRoot 'out'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $apkName = "album-Android-v$versionName.apk"
    $publishedApk = Join-Path $out $apkName
    Copy-Item -LiteralPath $apk -Destination $publishedApk -Force
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $publishedApk).Hash.ToLowerInvariant()
    "$hash  $apkName" | Set-Content -Encoding ASCII -LiteralPath (Join-Path $out 'SHA256SUMS.txt')
    [ordered]@{
        schema = 1
        version_name = $versionName
        version_code = $versionCode
        file_name = $apkName
        sha256 = $hash
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $out 'android-release-metadata.json')
    Write-Host "Android 构建、单测、Lint 和 APK 安全检查已通过：$versionName / $versionCode。"
}

if (-not $AndroidOnly) { Build-Windows }
if (-not $WindowsOnly) { Build-Android }
Write-Host '本地构建流程完成；提交到 main 后，正式三端构建与 Android 公共发布仍以 GitHub Actions 为准。'
