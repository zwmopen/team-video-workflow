Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Stop"
$LibraryRoot = "D:\Download\素材下载\团建视频"
$SourceRoot = Join-Path $LibraryRoot "01_原片素材库"
$SceneRoot = Join-Path $LibraryRoot "02_分镜素材库"
$AudioTextRoot = Join-Path $LibraryRoot "03_音频文案库"
$SmartWorkRoot = Join-Path $LibraryRoot "04_智能剪辑初剪库"
$OpsRoot = Join-Path $LibraryRoot "90_待整理与记录"
$CacheRoot = Join-Path $OpsRoot "00-模板库\素材库浏览器缓存"
$VideoExts = @(".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm")
$SystemNames = @("00-模板库", "._采集记录", "._系统记录", "._clean_report")
$script:AllItems = @()
$script:FilteredItems = @()
$script:SelectedItem = $null
$script:RenderLimit = 240

function Find-Ffmpeg {
    $candidates = @("C:\ffmpeg\bin\ffmpeg.exe", "D:\Program Files\江湖工具箱\JHlib\ffmpeg\ffmpeg.exe")
    foreach ($item in $candidates) {
        if (Test-Path -LiteralPath $item) { return $item }
    }
    return $null
}

function Get-StableId {
    param([string]$Text)
    $md5 = [System.Security.Cryptography.MD5]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        $hash = $md5.ComputeHash($bytes)
        return (($hash | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally {
        $md5.Dispose()
    }
}

function Test-SkipPath {
    param([string]$Path)
    foreach ($name in $SystemNames) {
        if ($Path -like "*\$name\*") { return $true }
    }
    if ($Path -like "*\._*") { return $true }
    return $false
}

function Get-SceneInfo {
    param([System.IO.FileInfo]$File, [System.IO.DirectoryInfo]$Root, [string]$Kind, [string]$Location)
    $rel = $File.FullName.Substring($Root.FullName.Length).TrimStart("\")
    $parts = $rel -split "\\"
    if ($Kind -eq "分镜素材") {
        $category = if ($parts.Length -ge 2) { $parts[0] } else { "" }
        $keyword = if ($parts.Length -ge 3) { $parts[1] } else { "" }
    } elseif ($Kind -eq "未分类/未整理素材") {
        $category = "未分类/未整理素材"
        $keyword = if ($parts.Length -ge 2) { $parts[-2] } else { "待整理" }
    } elseif ($parts.Length -ge 3 -and $parts[0] -match "小红书|XHS|抖音|采集") {
        $category = "原片补素材"
        $keyword = $parts[1]
    } else {
        $category = "已整理原片"
        $keyword = if ($parts.Length -ge 2) { $parts[-2] } else { "" }
    }
    [pscustomobject]@{
        Id = Get-StableId $File.FullName
        Kind = $Kind
        Location = $Location
        Category = $category
        Keyword = $keyword
        Name = $File.Name
        Path = $File.FullName
        SizeMB = [math]::Round($File.Length / 1MB, 2)
        Modified = $File.LastWriteTime.ToString("yyyy-MM-dd HH:mm")
    }
}

function Add-Videos {
    param([System.Collections.Generic.List[object]]$Items, [System.IO.DirectoryInfo]$Root, [string]$Kind, [string]$Location)
    Get-ChildItem -LiteralPath $Root.FullName -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $VideoExts -contains $_.Extension.ToLowerInvariant() -and -not (Test-SkipPath $_.FullName) } |
        ForEach-Object { $Items.Add((Get-SceneInfo -File $_ -Root $Root -Kind $Kind -Location $Location)) }
}

function Scan-Library {
    if (-not (Test-Path -LiteralPath $LibraryRoot)) { return @() }
    $items = New-Object System.Collections.Generic.List[object]
    $inbox = Join-Path $OpsRoot "00-待分类整理库"
    if (Test-Path -LiteralPath $inbox) {
        Add-Videos -Items $items -Root (Get-Item -LiteralPath $inbox) -Kind "未分类/未整理素材" -Location "待整理"
    }
    if (Test-Path -LiteralPath $SourceRoot) {
        Get-ChildItem -LiteralPath $SourceRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*-原视频素材" } |
            ForEach-Object { Add-Videos -Items $items -Root $_ -Kind "已整理原片" -Location ($_.Name -replace "-原视频素材$", "") }
    }
    if (Test-Path -LiteralPath $SceneRoot) {
        Get-ChildItem -LiteralPath $SceneRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*智能镜头分类" } |
            ForEach-Object { Add-Videos -Items $items -Root $_ -Kind "分镜素材" -Location ($_.Name -replace "智能镜头分类$", "") }
    }
    Get-ChildItem -LiteralPath $LibraryRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*-原视频素材" -or $_.Name -like "*智能镜头分类" } |
        ForEach-Object {
            if (Test-SkipPath $_.FullName) { return }
            if ($_.Name -like "*-原视频素材") {
                Add-Videos -Items $items -Root $_ -Kind "已整理原片" -Location ($_.Name -replace "-原视频素材$", "")
            } else {
                Add-Videos -Items $items -Root $_ -Kind "分镜素材" -Location ($_.Name -replace "智能镜头分类$", "")
            }
        }
    return $items | Sort-Object Location, Kind, Category, Keyword, Name
}

function Set-ComboValues {
    param([System.Windows.Forms.ComboBox]$Combo, [object[]]$Values)
    $current = $Combo.Text
    $Combo.Items.Clear()
    [void]$Combo.Items.Add("全部")
    foreach ($v in ($Values | Where-Object { $_ } | Sort-Object -Unique)) {
        [void]$Combo.Items.Add([string]$v)
    }
    if ($Combo.Items.Contains($current)) { $Combo.Text = $current } else { $Combo.Text = "全部" }
}

function New-SoftButton {
    param([string]$Text, [int]$X, [int]$Y, [int]$W, [bool]$Primary = $false)
    $button = New-Object System.Windows.Forms.Button
    $button.Text = $Text
    $button.Left = $X
    $button.Top = $Y
    $button.Width = $W
    $button.Height = 34
    $button.FlatStyle = "Flat"
    $button.FlatAppearance.BorderSize = 0
    $button.BackColor = if ($Primary) { [System.Drawing.Color]::FromArgb(48,126,255) } else { [System.Drawing.Color]::FromArgb(238,244,248) }
    $button.ForeColor = if ($Primary) { [System.Drawing.Color]::White } else { [System.Drawing.Color]::FromArgb(28,41,56) }
    return $button
}

function Get-ThumbPath {
    param($Item)
    if (-not (Test-Path -LiteralPath $CacheRoot)) { New-Item -ItemType Directory -Path $CacheRoot -Force | Out-Null }
    $thumb = Join-Path $CacheRoot "$($Item.Id).jpg"
    if (Test-Path -LiteralPath $thumb) { return $thumb }
    $ffmpeg = Find-Ffmpeg
    if (-not $ffmpeg) { return $null }
    $args = @("-hide_banner","-loglevel","error","-y","-ss","00:00:01.000","-i",$Item.Path,"-frames:v","1","-vf","scale=240:-1","-q:v","4",$thumb)
    Start-Process -FilePath $ffmpeg -ArgumentList $args -WindowStyle Hidden -Wait | Out-Null
    if (Test-Path -LiteralPath $thumb) { return $thumb }
    return $null
}

function Set-Preview {
    param($Item)
    $script:SelectedItem = $Item
    if (-not $Item) { return }
    $titleLabel.Text = $Item.Name
    $metaLabel.Text = "$($Item.Kind) / $($Item.Location) / $($Item.Category) / $($Item.Keyword)`r`n$($Item.SizeMB) MB    $($Item.Modified)"
    $pathBox.Text = $Item.Path
    $thumb = Get-ThumbPath $Item
    if ($thumb -and (Test-Path -LiteralPath $thumb)) {
        if ($previewBox.Image) { $previewBox.Image.Dispose(); $previewBox.Image = $null }
        $stream = [System.IO.File]::OpenRead($thumb)
        try { $previewBox.Image = [System.Drawing.Image]::FromStream($stream).Clone() } finally { $stream.Close() }
    }
}

function Get-SelectedPaths {
    $paths = New-Object System.Collections.Generic.List[string]
    foreach ($row in $tileList.SelectedItems) {
        if ($row.Tag -and (Test-Path -LiteralPath $row.Tag.Path)) { $paths.Add($row.Tag.Path) }
    }
    return [string[]]$paths
}

function Apply-Filter {
    $q = $searchBox.Text.Trim()
    $kind = $kindCombo.Text
    $location = $locationCombo.Text
    $category = $categoryCombo.Text
    $keyword = $keywordCombo.Text
    $script:FilteredItems = @($script:AllItems | Where-Object {
        ($kind -eq "全部" -or $_.Kind -eq $kind) -and
        ($location -eq "全部" -or $_.Location -eq $location) -and
        ($category -eq "全部" -or $_.Category -eq $category) -and
        ($keyword -eq "全部" -or $_.Keyword -eq $keyword) -and
        ($q -eq "" -or $_.Name -like "*$q*" -or $_.Keyword -like "*$q*" -or $_.Category -like "*$q*" -or $_.Path -like "*$q*")
    })

    $tileList.BeginUpdate()
    $tileList.Items.Clear()
    $images.Images.Clear()
    $count = 0
    foreach ($item in ($script:FilteredItems | Select-Object -First $script:RenderLimit)) {
        $thumb = Get-ThumbPath $item
        if ($thumb -and (Test-Path -LiteralPath $thumb)) {
            try {
                $stream = [System.IO.File]::OpenRead($thumb)
                try { $images.Images.Add($item.Id, [System.Drawing.Image]::FromStream($stream).Clone()) } finally { $stream.Close() }
            } catch {}
        }
        if (-not $images.Images.ContainsKey($item.Id)) {
            $bmp = New-Object System.Drawing.Bitmap(160, 210)
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.Clear([System.Drawing.Color]::FromArgb(224,233,241))
            $g.DrawString("无预览", $form.Font, [System.Drawing.Brushes]::DimGray, 48, 92)
            $g.Dispose()
            $images.Images.Add($item.Id, $bmp)
        }
        $text = "$($item.Keyword)`r`n$($item.Name)"
        $node = New-Object System.Windows.Forms.ListViewItem($text)
        $node.ImageKey = $item.Id
        $node.Tag = $item
        [void]$tileList.Items.Add($node)
        $count++
    }
    $tileList.EndUpdate()
    $extra = if ($script:FilteredItems.Count -gt $script:RenderLimit) { "，当前先显示 $script:RenderLimit 条，继续缩小筛选会更快" } else { "" }
    $status.Text = "总素材 $($script:AllItems.Count) 条，当前 $($script:FilteredItems.Count) 条$extra。选中封面后直接拖到剪映或桌面。"
}

function Refresh-Filters {
    Set-ComboValues $kindCombo ($script:AllItems | ForEach-Object Kind)
    Set-ComboValues $locationCombo ($script:AllItems | ForEach-Object Location)
    Set-ComboValues $categoryCombo ($script:AllItems | ForEach-Object Category)
    Set-ComboValues $keywordCombo ($script:AllItems | ForEach-Object Keyword)
}

function Reveal-Selected {
    $paths = Get-SelectedPaths
    if ($paths.Count -eq 0) { return }
    Start-Process explorer.exe -ArgumentList "/select,`"$($paths[0])`""
}

function Open-Selected {
    $paths = Get-SelectedPaths
    if ($paths.Count -eq 0) { return }
    Start-Process -LiteralPath $paths[0]
}

function Copy-SelectedPaths {
    $paths = Get-SelectedPaths
    if ($paths.Count -eq 0) { return }
    [System.Windows.Forms.Clipboard]::SetText(($paths -join "`r`n"))
    $status.Text = "已复制 $($paths.Count) 条路径。"
}

if ($env:MATERIAL_TOOL_SELFTEST -eq "1") {
    $items = @(Scan-Library)
    "self-test ok: $($items.Count) items"
    exit 0
}

$form = New-Object System.Windows.Forms.Form
$form.Text = "团建视频剪辑工作流 - 本地文件拖拽版"
$form.StartPosition = "CenterScreen"
$form.Width = 1420
$form.Height = 860
$form.MinimumSize = New-Object System.Drawing.Size(1060, 680)
$form.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 9)
$form.BackColor = [System.Drawing.Color]::FromArgb(217,228,237)

$header = New-Object System.Windows.Forms.Panel
$header.Dock = "Top"
$header.Height = 76
$header.BackColor = [System.Drawing.Color]::FromArgb(230,238,244)
$form.Controls.Add($header)

$appTitle = New-Object System.Windows.Forms.Label
$appTitle.Text = "团建视频剪辑工作流"
$appTitle.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 16, [System.Drawing.FontStyle]::Bold)
$appTitle.Left = 18; $appTitle.Top = 14; $appTitle.Width = 260; $appTitle.Height = 28
$header.Controls.Add($appTitle)

$appSub = New-Object System.Windows.Forms.Label
$appSub.Text = "素材采集、素材整理、文案配镜、成品检查"
$appSub.Left = 20; $appSub.Top = 45; $appSub.Width = 330; $appSub.ForeColor = [System.Drawing.Color]::FromArgb(96,111,128)
$header.Controls.Add($appSub)

$tabNames = @("采集入库", "素材整理", "文案配镜/初剪", "成品检查")
$x = 390
foreach ($name in $tabNames) {
    $btn = New-SoftButton -Text $name -X $x -Y 21 -W 120 -Primary ($name -eq "素材整理")
    $header.Controls.Add($btn)
    $x += 132
}

$status = New-Object System.Windows.Forms.Label
$status.Dock = "Bottom"
$status.Height = 28
$status.Padding = New-Object System.Windows.Forms.Padding(14, 5, 14, 4)
$status.BackColor = [System.Drawing.Color]::FromArgb(230,238,244)
$form.Controls.Add($status)

$mainSplit = New-Object System.Windows.Forms.SplitContainer
$mainSplit.Dock = "Fill"
$mainSplit.SplitterWidth = 7
$mainSplit.SplitterDistance = 300
$mainSplit.Panel1MinSize = 230
$mainSplit.Panel2MinSize = 640
$form.Controls.Add($mainSplit)

$left = $mainSplit.Panel1
$left.BackColor = [System.Drawing.Color]::FromArgb(226,235,241)

$searchLabel = New-Object System.Windows.Forms.Label
$searchLabel.Text = "搜索"
$searchLabel.Left = 18; $searchLabel.Top = 18
$left.Controls.Add($searchLabel)

$searchBox = New-Object System.Windows.Forms.TextBox
$searchBox.Left = 18; $searchBox.Top = 42; $searchBox.Width = 250
$left.Controls.Add($searchBox)

$kindCombo = New-Object System.Windows.Forms.ComboBox
$locationCombo = New-Object System.Windows.Forms.ComboBox
$categoryCombo = New-Object System.Windows.Forms.ComboBox
$keywordCombo = New-Object System.Windows.Forms.ComboBox
$labels = @("素材类型", "地点", "一级分类", "具体场景/素材组")
$combos = @($kindCombo, $locationCombo, $categoryCombo, $keywordCombo)
$topY = 86
for ($i=0; $i -lt $combos.Count; $i++) {
    $lab = New-Object System.Windows.Forms.Label
    $lab.Text = $labels[$i]
    $lab.Left = 18; $lab.Top = $topY
    $left.Controls.Add($lab)
    $combo = $combos[$i]
    $combo.Left = 18; $combo.Top = $topY + 24; $combo.Width = 250
    $combo.DropDownStyle = "DropDownList"
    $left.Controls.Add($combo)
    $topY += 70
}

$refreshBtn = New-SoftButton -Text "重新扫描" -X 18 -Y ($topY + 10) -W 112 -Primary $true
$resetBtn = New-SoftButton -Text "重置筛选" -X 142 -Y ($topY + 10) -W 112 -Primary $false
$left.Controls.Add($refreshBtn)
$left.Controls.Add($resetBtn)

$note = New-Object System.Windows.Forms.Label
$note.Text = "这里是真正用于剪辑拖拽的本地工具。中间封面拖出去时，其他软件收到的是视频文件本体，不是网页链接。"
$note.Left = 18; $note.Top = $topY + 62; $note.Width = 250; $note.Height = 90
$note.ForeColor = [System.Drawing.Color]::FromArgb(96,111,128)
$left.Controls.Add($note)

$rightSplit = New-Object System.Windows.Forms.SplitContainer
$rightSplit.Dock = "Fill"
$rightSplit.SplitterWidth = 7
$rightSplit.SplitterDistance = 760
$rightSplit.Panel1MinSize = 420
$rightSplit.Panel2MinSize = 280
$mainSplit.Panel2.Controls.Add($rightSplit)

$images = New-Object System.Windows.Forms.ImageList
$images.ImageSize = New-Object System.Drawing.Size(132, 176)
$images.ColorDepth = [System.Windows.Forms.ColorDepth]::Depth32Bit

$tileList = New-Object System.Windows.Forms.ListView
$tileList.Dock = "Fill"
$tileList.View = "LargeIcon"
$tileList.LargeImageList = $images
$tileList.MultiSelect = $true
$tileList.HideSelection = $false
$tileList.BackColor = [System.Drawing.Color]::FromArgb(226,235,241)
$tileList.BorderStyle = "None"
$tileList.AllowDrop = $false
$rightSplit.Panel1.Controls.Add($tileList)

$previewPanel = $rightSplit.Panel2
$previewPanel.BackColor = [System.Drawing.Color]::FromArgb(226,235,241)

$previewBox = New-Object System.Windows.Forms.PictureBox
$previewBox.Left = 16; $previewBox.Top = 16; $previewBox.Width = 330; $previewBox.Height = 430
$previewBox.Anchor = "Top,Left,Right"
$previewBox.SizeMode = "Zoom"
$previewBox.BackColor = [System.Drawing.Color]::Black
$previewPanel.Controls.Add($previewBox)

$titleLabel = New-Object System.Windows.Forms.Label
$titleLabel.Left = 16; $titleLabel.Top = 460; $titleLabel.Width = 330; $titleLabel.Height = 42
$titleLabel.Anchor = "Top,Left,Right"
$titleLabel.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 10, [System.Drawing.FontStyle]::Bold)
$previewPanel.Controls.Add($titleLabel)

$metaLabel = New-Object System.Windows.Forms.Label
$metaLabel.Left = 16; $metaLabel.Top = 506; $metaLabel.Width = 330; $metaLabel.Height = 54
$metaLabel.Anchor = "Top,Left,Right"
$metaLabel.ForeColor = [System.Drawing.Color]::FromArgb(96,111,128)
$previewPanel.Controls.Add($metaLabel)

$openBtn = New-SoftButton -Text "播放预览" -X 16 -Y 572 -W 96 -Primary $true
$revealBtn = New-SoftButton -Text "打开位置" -X 122 -Y 572 -W 96 -Primary $false
$copyBtn = New-SoftButton -Text "复制路径" -X 228 -Y 572 -W 96 -Primary $false
$previewPanel.Controls.Add($openBtn)
$previewPanel.Controls.Add($revealBtn)
$previewPanel.Controls.Add($copyBtn)

$pathBox = New-Object System.Windows.Forms.TextBox
$pathBox.Left = 16; $pathBox.Top = 620; $pathBox.Width = 330; $pathBox.Height = 70
$pathBox.Anchor = "Top,Left,Right"
$pathBox.Multiline = $true
$pathBox.ReadOnly = $true
$pathBox.ScrollBars = "Vertical"
$previewPanel.Controls.Add($pathBox)

$filterHandler = { Apply-Filter }
$searchBox.Add_TextChanged($filterHandler)
$kindCombo.Add_SelectedIndexChanged($filterHandler)
$locationCombo.Add_SelectedIndexChanged($filterHandler)
$categoryCombo.Add_SelectedIndexChanged($filterHandler)
$keywordCombo.Add_SelectedIndexChanged($filterHandler)

$refreshBtn.Add_Click({
    $status.Text = "正在扫描素材库..."
    $form.Refresh()
    $script:AllItems = @(Scan-Library)
    Refresh-Filters
    Apply-Filter
})
$resetBtn.Add_Click({
    $searchBox.Text = ""
    foreach ($combo in $combos) { $combo.Text = "全部" }
    Apply-Filter
})
$openBtn.Add_Click({ Open-Selected })
$revealBtn.Add_Click({ Reveal-Selected })
$copyBtn.Add_Click({ Copy-SelectedPaths })
$tileList.Add_DoubleClick({ Open-Selected })
$tileList.Add_SelectedIndexChanged({
    if ($tileList.SelectedItems.Count -gt 0) { Set-Preview $tileList.SelectedItems[0].Tag }
})
$tileList.Add_ItemDrag({
    $paths = Get-SelectedPaths
    if ($paths.Count -eq 0) { return }
    $data = New-Object System.Windows.Forms.DataObject
    $data.SetData([System.Windows.Forms.DataFormats]::FileDrop, $paths)
    $data.SetData([System.Windows.Forms.DataFormats]::Text, ($paths -join "`r`n"))
    [void]$tileList.DoDragDrop($data, [System.Windows.Forms.DragDropEffects]::Copy)
})

$form.Add_Shown({
    $status.Text = "正在扫描素材库..."
    $form.Refresh()
    $script:AllItems = @(Scan-Library)
    Refresh-Filters
    Apply-Filter
})

[void]$form.ShowDialog()
