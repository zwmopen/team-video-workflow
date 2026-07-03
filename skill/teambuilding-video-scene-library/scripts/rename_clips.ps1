param(
  [Parameter(Mandatory=$true)][string]$LibraryRoot,
  [bool]$Move = $true
)

$ToolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
Push-Location $ToolRoot
try {
  python main.py rename-clips $LibraryRoot --move $Move
} finally {
  Pop-Location
}
