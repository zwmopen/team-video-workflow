param(
  [Parameter(Mandatory=$true)]
  [string]$ReferenceVideo,
  [Parameter(Mandatory=$true)]
  [string]$LibraryRoot,
  [Parameter(Mandatory=$true)]
  [string]$Title,
  [string]$Output = "",
  [string]$ScriptFile = "",
  [string]$ScriptText = "",
  [switch]$Transcribe,
  [int]$MaxBeats = 0
)

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "$toolRoot\main.py",
  "recompose-reference",
  $ReferenceVideo,
  $LibraryRoot,
  "--title", $Title
)

if ($Output -ne "") {
  $argsList += @("--output", $Output)
}
if ($ScriptFile -ne "") {
  $argsList += @("--script-file", $ScriptFile)
}
if ($ScriptText -ne "") {
  $argsList += @("--script-text", $ScriptText)
}
if ($Transcribe) {
  $argsList += "--transcribe"
}
if ($MaxBeats -gt 0) {
  $argsList += @("--max-beats", $MaxBeats)
}

Push-Location $toolRoot
try {
  python @argsList
  if ($LASTEXITCODE -ne 0) {
    throw "Reference recompose failed."
  }
} finally {
  Pop-Location
}
