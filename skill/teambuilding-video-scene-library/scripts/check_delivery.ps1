param(
  [Parameter(Mandatory=$true)]
  [string]$OutputDir,
  [bool]$ExpectVertical = $true,
  [int]$MinPackClips = 1
)

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"

Push-Location $toolRoot
try {
  python "$toolRoot\main.py" check-delivery $OutputDir --expect-vertical $ExpectVertical --min-pack-clips $MinPackClips
  if ($LASTEXITCODE -ne 0) {
    throw "Delivery check failed."
  }
} finally {
  Pop-Location
}
