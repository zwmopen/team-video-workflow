param(
  [Parameter(Mandatory=$true)]
  [string]$InputVideo,

  [Parameter(Mandatory=$true)]
  [string]$OutputVideo,

  [string]$Mode = "opencv",
  [int]$YMin = 900,
  [int]$YMax = 1920,
  [int]$XMin = 0,
  [int]$XMax = 1080
)

$repo = "D:\AICode\AI\tools\external-video-reference\video-subtitle-remover"
$python = Join-Path $repo ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
  throw "VSR environment is missing: $python"
}

$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = "True"
Push-Location $repo
try {
  & $python "backend\main.py" `
    -i $InputVideo `
    -o $OutputVideo `
    --subtitle-area-coords $YMin $YMax $XMin $XMax `
    --inpaint-mode $Mode
  exit $LASTEXITCODE
}
finally {
  Pop-Location
}
