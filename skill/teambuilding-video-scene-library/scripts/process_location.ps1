param(
  [Parameter(Mandatory=$true)]
  [string]$InputDir,
  [string]$Output = "",
  [ValidateSet("vertical","all")]
  [string]$Orientation = "vertical",
  [ValidateSet("adaptive","content","transnet")]
  [string]$Detector = "adaptive",
  [ValidateSet("accurate","copy")]
  [string]$SplitMode = "accurate",
  [switch]$DryRun,
  [switch]$ForceReprocess,
  [int]$MaxVideos = 0,
  [int]$MaxScenesPerVideo = 0
)

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "$toolRoot\main.py",
  "process-location",
  $InputDir,
  "--orientation", $Orientation,
  "--detector", $Detector,
  "--split-mode", $SplitMode
)

if ($Output -ne "") {
  $argsList += @("--output", $Output)
}
if ($DryRun) {
  $argsList += "--dry-run"
}
if ($ForceReprocess) {
  $argsList += "--force-reprocess"
}
if ($MaxVideos -gt 0) {
  $argsList += @("--max-videos", $MaxVideos)
}
if ($MaxScenesPerVideo -gt 0) {
  $argsList += @("--max-scenes-per-video", $MaxScenesPerVideo)
}

Push-Location $toolRoot
try {
  python @argsList
} finally {
  Pop-Location
}
