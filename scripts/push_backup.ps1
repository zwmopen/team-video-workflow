param(
  [string]$Message = "Update team video workflow backup"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

git status --short
git add -A
git commit -m $Message
git push
