param(
  [Parameter(Mandatory=$true)]
  [string]$LibraryRoot,
  [Parameter(Mandatory=$true)]
  [string]$Title,
  [string]$ScriptFile = "",
  [string]$ScriptText = "",
  [string]$Output = ""
)

$toolRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$argsList = @(
  "$toolRoot\main.py",
  "build-edit-pack",
  $LibraryRoot,
  "--title", $Title
)

if ($ScriptFile -ne "") {
  $argsList += @("--script-file", $ScriptFile)
} elseif ($ScriptText -ne "") {
  $argsList += @("--script-text", $ScriptText)
} else {
  throw "Provide -ScriptFile or -ScriptText"
}

if ($Output -ne "") {
  $argsList += @("--output", $Output)
}

Push-Location $toolRoot
try {
  python @argsList
} finally {
  Pop-Location
}
