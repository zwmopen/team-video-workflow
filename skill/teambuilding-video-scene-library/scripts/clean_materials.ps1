param(
  [Parameter(Mandatory=$true)]
  [string]$InputDir,

  [Parameter(Mandatory=$true)]
  [string]$OutputDir,

  [ValidateSet("crop-bottom", "blur-bottom", "adaptive-crop", "opencv-inpaint-text")]
  [string]$Mode = "crop-bottom",
  [double]$BottomPct = 0.28,
  [double]$TopPct = 0.0,
  [int]$MaxFiles = 0
)

$ErrorActionPreference = "Stop"
$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "main.py",
  "clean-materials",
  $InputDir,
  "--output",
  $OutputDir,
  "--mode",
  $Mode,
  "--bottom-pct",
  "$BottomPct",
  "--top-pct",
  "$TopPct"
)

if ($MaxFiles -gt 0) {
  $argsList += @("--max-files", "$MaxFiles")
}

Push-Location $toolRoot
try {
  python @argsList
}
finally {
  Pop-Location
}
