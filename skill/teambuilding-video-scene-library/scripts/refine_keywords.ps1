param(
  [Parameter(Mandatory=$true)]
  [string]$LibraryRoot,
  [bool]$Ocr = $false,
  [bool]$Transcript = $true,
  [bool]$Move = $true
)

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "$toolRoot\main.py",
  "refine-keywords",
  $LibraryRoot,
  "--ocr", $Ocr.ToString().ToLower(),
  "--transcript", $Transcript.ToString().ToLower(),
  "--move", $Move.ToString().ToLower()
)

Push-Location $toolRoot
try {
  python @argsList
} finally {
  Pop-Location
}
