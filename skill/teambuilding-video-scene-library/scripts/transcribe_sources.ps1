param(
  [Parameter(Mandatory=$true)]
  [string]$LibraryRoot,
  [string]$Model = "tiny",
  [string]$Language = "zh",
  [int]$MaxSources = 0,
  [switch]$Force
)

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "$toolRoot\main.py",
  "transcribe-sources",
  $LibraryRoot,
  "--model", $Model,
  "--language", $Language
)

if ($MaxSources -gt 0) {
  $argsList += @("--max-sources", $MaxSources)
}
if ($Force) {
  $argsList += "--force"
}

Push-Location $toolRoot
try {
  python @argsList
} finally {
  Pop-Location
}
