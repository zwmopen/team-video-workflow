param(
  [Parameter(Mandatory=$true)]
  [string]$SourceRoot,
  [Parameter(Mandatory=$true)]
  [string]$OutputRoot,
  [Parameter(Mandatory=$true)]
  [string]$Location,
  [bool]$Move = $true
)

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "$toolRoot\main.py",
  "collect-location-sources",
  $SourceRoot,
  "--output-root", $OutputRoot,
  "--location", $Location,
  "--move", $Move.ToString().ToLower()
)

Push-Location $toolRoot
try {
  python @argsList
} finally {
  Pop-Location
}
