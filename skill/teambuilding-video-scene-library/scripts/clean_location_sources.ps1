param(
  [Parameter(Mandatory=$true)][string]$OutputRoot,
  [Parameter(Mandatory=$true)][string]$Location,
  [bool]$Move = $true
)

$ToolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
Push-Location $ToolRoot
try {
  python main.py clean-location-sources --output-root $OutputRoot --location $Location --move $Move
} finally {
  Pop-Location
}
