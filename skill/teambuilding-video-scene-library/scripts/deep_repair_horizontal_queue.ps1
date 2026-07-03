param(
  [string]$QueueCsv = "",
  [string]$OutputRoot = "",
  [string]$Mode = "sttn-auto",
  [int]$MaxItems = 0,
  [double]$YMinRatio = 0.68,
  [double]$YMaxRatio = 1.0,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Decode-Utf8Base64([string]$Value) {
  return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

if (-not $QueueCsv) {
  $QueueCsv = Decode-Utf8Base64 "RDpcRG93bmxvYWRc57Sg5p2Q5LiL6L29XOWbouW7uuinhumikVwuX+mHh+mbhuiusOW9lVxob3Jpem9udGFsX3NvdXJjZV9kZWVwX3JlcGFpcl9xdWV1ZV8yMDI2MDcwMy5jc3Y="
}
if (-not $OutputRoot) {
  $OutputRoot = Decode-Utf8Base64 "RDpcRG93bmxvYWRc57Sg5p2Q5LiL6L29XOWbouW7uuinhumikVzmt7Hluqbkv67lpI3mqKrlsY/ljp/niYc="
}

if (-not (Test-Path -LiteralPath $QueueCsv)) {
  throw "Queue CSV not found: $QueueCsv"
}

$repoRoot = "D:\AICode\AI\tools\teambuilding-video-scene-library"
$env:PYTHONPATH = Join-Path $repoRoot "src"
$vsr = "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\vsr_clean.ps1"
$recordRoot = Decode-Utf8Base64 "RDpcRG93bmxvYWRc57Sg5p2Q5LiL6L29XOWbouW7uuinhumikVwuX+mHh+mbhuiusOW9lQ=="
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
New-Item -ItemType Directory -Force -Path $recordRoot | Out-Null

$runStamp = Get-Date -Format "yyyyMMdd_HHmmss"
$runLog = Join-Path $recordRoot "deep_repair_horizontal_run_$runStamp.csv"
$rows = Import-Csv -LiteralPath $QueueCsv | Where-Object { $_.exists -eq "True" -or $_.exists -eq "true" -or $_.exists -eq $true }
if ($MaxItems -gt 0) {
  $rows = $rows | Select-Object -First $MaxItems
}

$results = @()
foreach ($row in $rows) {
  $source = $row.source
  $location = if ($row.location) { $row.location } else { "unclassified" }
  $width = [int]$row.width
  $height = [int]$row.height
  if ($width -le 0 -or $height -le 0) {
    $results += [pscustomobject]@{
      time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
      status = "skip_bad_dimension"
      source = $source
      output = ""
      mode = $Mode
      width = $width
      height = $height
      elapsed_seconds = 0
      error = "bad dimension"
    }
    continue
  }

  $safeName = [IO.Path]::GetFileNameWithoutExtension($source)
  foreach ($bad in [IO.Path]::GetInvalidFileNameChars()) {
    $safeName = $safeName.Replace($bad, "_")
  }
  $locationDir = Join-Path $OutputRoot $location
  New-Item -ItemType Directory -Force -Path $locationDir | Out-Null
  $output = Join-Path $locationDir "$safeName`__deep_repair_$Mode.mp4"

  if (Test-Path -LiteralPath $output) {
    $results += [pscustomobject]@{
      time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
      status = "skip_existing"
      source = $source
      output = $output
      mode = $Mode
      width = $width
      height = $height
      elapsed_seconds = 0
      error = ""
    }
    continue
  }

  $yMin = [Math]::Max(0, [int][Math]::Floor($height * $YMinRatio))
  $yMax = [Math]::Min($height, [int][Math]::Ceiling($height * $YMaxRatio))
  $xMin = 0
  $xMax = $width

  if ($DryRun) {
    $results += [pscustomobject]@{
      time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
      status = "dry_run"
      source = $source
      output = $output
      mode = $Mode
      width = $width
      height = $height
      elapsed_seconds = 0
      error = "coords $yMin $yMax $xMin $xMax"
    }
    continue
  }

  $sw = [Diagnostics.Stopwatch]::StartNew()
  $status = "written"
  $err = ""
  try {
    & $vsr -InputVideo $source -OutputVideo $output -Mode $Mode -YMin $yMin -YMax $yMax -XMin $xMin -XMax $xMax
    if (-not (Test-Path -LiteralPath $output)) {
      $status = "failed"
      $err = "output not created"
    }
  } catch {
    $status = "failed"
    $err = $_.Exception.Message
  } finally {
    $sw.Stop()
  }

  $results += [pscustomobject]@{
    time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    status = $status
    source = $source
    output = $output
    mode = $Mode
    width = $width
    height = $height
    elapsed_seconds = [Math]::Round($sw.Elapsed.TotalSeconds, 2)
    error = $err
  }
  $results | Export-Csv -LiteralPath $runLog -NoTypeInformation -Encoding UTF8
}

$results | Export-Csv -LiteralPath $runLog -NoTypeInformation -Encoding UTF8
$summary = $results | Group-Object status | Select-Object Name, Count
[pscustomobject]@{
  queue = $QueueCsv
  output_root = $OutputRoot
  mode = $Mode
  count = $results.Count
  summary = $summary
  log = $runLog
}
