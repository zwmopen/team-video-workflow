param(
  [Parameter(Mandatory=$true)]
  [string]$LibraryRoot,

  [string]$Output = "",
  [ValidateSet("folder", "category", "source")]
  [string]$GroupBy = "folder",
  [int]$ClipsPerSheet = 12,
  [int]$MaxClips = 0
)

$ErrorActionPreference = "Stop"
$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "main.py",
  "visual-audit",
  $LibraryRoot,
  "--group-by",
  $GroupBy,
  "--clips-per-sheet",
  "$ClipsPerSheet"
)

if ($Output -ne "") {
  $argsList += @("--output", $Output)
}

if ($MaxClips -gt 0) {
  $argsList += @("--max-clips", "$MaxClips")
}

Push-Location $toolRoot
try {
  python @argsList
}
finally {
  Pop-Location
}
