param(
  [Parameter(Mandatory=$true)]
  [string]$Location,
  [string]$InboxRoot = "",
  [string]$OutputRoot = "",
  [string]$ScriptFile = "",
  [string]$ReferenceVideo = "",
  [string]$Title = "",
  [ValidateSet("vertical","all")]
  [string]$Orientation = "vertical",
  [switch]$SkipIngest,
  [switch]$SkipProcess,
  [switch]$ForceReprocess,
  [int]$MaxVideos = 0
)

function New-Text {
  param([int[]]$CodePoints)
  return -join ($CodePoints | ForEach-Object { [char]$_ })
}

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"

$materialDownload = New-Text @(32032,26448,19979,36733)
$teamVideo = New-Text @(22242,24314,35270,39057)
$inboxName = "00-" + (New-Text @(24453,20998,31867,25972,29702,24211))
$sourceSuffix = "-" + (New-Text @(21407,35270,39057,32032,26448))
$librarySuffix = New-Text @(26234,33021,38236,22836,20998,31867)
$editPackSuffix = "_" + (New-Text @(26234,33021,37197,38236))

if ($InboxRoot -eq "") {
  $InboxRoot = Join-Path (Join-Path "D:\Download" $materialDownload) (Join-Path $teamVideo $inboxName)
}
if ($OutputRoot -eq "") {
  $OutputRoot = Join-Path (Join-Path "D:\Download" $materialDownload) $teamVideo
}

$sourceDir = Join-Path $OutputRoot "$($Location)$sourceSuffix"
$libraryRoot = Join-Path $OutputRoot "$($Location)$librarySuffix"
$recomposeOutput = ""

function Invoke-Tool {
  param([string[]]$ArgsList)
  Push-Location $toolRoot
  try {
    python @ArgsList
    if ($LASTEXITCODE -ne 0) {
      throw "Command failed: python $($ArgsList -join ' ')"
    }
  } finally {
    Pop-Location
  }
}

if (-not $SkipIngest) {
  Invoke-Tool @(
    "$toolRoot\main.py",
    "collect-location-sources",
    $InboxRoot,
    "--output-root", $OutputRoot,
    "--location", $Location,
    "--move", "true"
  )

  Invoke-Tool @(
    "$toolRoot\main.py",
    "clean-location-sources",
    "--output-root", $OutputRoot,
    "--location", $Location,
    "--move", "true"
  )
}

if (-not $SkipProcess) {
  $processArgs = @(
    "$toolRoot\main.py",
    "process-location",
    $sourceDir,
    "--output", $libraryRoot,
    "--orientation", $Orientation,
    "--detector", "adaptive",
    "--split-mode", "accurate"
  )
  if ($ForceReprocess) {
    $processArgs += "--force-reprocess"
  }
  if ($MaxVideos -gt 0) {
    $processArgs += @("--max-videos", $MaxVideos)
  }
  Invoke-Tool $processArgs

  Invoke-Tool @(
    "$toolRoot\main.py",
    "rename-clips",
    $libraryRoot,
    "--move", "true"
  )
}

if ($ScriptFile -ne "") {
  if ($Title -eq "") {
    $Title = "$($Location)$editPackSuffix"
  }
  Invoke-Tool @(
    "$toolRoot\main.py",
    "build-edit-pack",
    $libraryRoot,
    "--script-file", $ScriptFile,
    "--title", $Title
  )
}

if ($ReferenceVideo -ne "") {
  if ($Title -eq "") {
    $Title = "$($Location)$editPackSuffix"
  }
  $safeTitle = ($Title -replace '[\\/:*?"<>|]', '_').Trim()
  $recomposeOutput = Join-Path $OutputRoot "$($safeTitle)_换画面粗剪"
  $recomposeArgs = @(
    "$toolRoot\main.py",
    "recompose-reference",
    $ReferenceVideo,
    $libraryRoot,
    "--title", $Title,
    "--output", $recomposeOutput
  )
  if ($ScriptFile -ne "") {
    $recomposeArgs += @("--script-file", $ScriptFile)
  }
  Invoke-Tool $recomposeArgs

  Invoke-Tool @(
    "$toolRoot\main.py",
    "check-delivery",
    $recomposeOutput,
    "--expect-vertical", "true",
    "--min-pack-clips", "1"
  )
}

Write-Host "Done."
Write-Host "Source: $sourceDir"
Write-Host "Library: $libraryRoot"
if ($recomposeOutput -ne "") {
  Write-Host "Rough cut output: $recomposeOutput"
}
