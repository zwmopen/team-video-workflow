---
name: teambuilding-video-scene-library
description: "Use when the user gives a local folder of team-building, travel, Douyin, or vlog videos and wants Codex to build a reusable scene-shot material library: scan videos, keep vertical clips when requested, split shots, remove audio, extract keyframes, classify scenes, deduplicate, preserve source timecodes, and create a same-level folder such as 千岛湖智能镜头分类 for Jianying/CapCut/Premiere editing."
---

# 团建视频分镜素材库

Use this skill as the reusable workflow for turning a location folder of edited team-building Vlogs into a clean shot-level material library.
Also use it after a library exists to build a numbered edit material pack from a new Douyin script.
Also use it when the user gives a reference video and wants to keep that video's audio/script rhythm while replacing the visuals with vertical clips from the reusable library.

The first production tool lives at:

```text
D:\AICode\AI\tools\teambuilding-video-scene-library
```

The local browser UI for reviewing the material library lives at:

```text
D:\AICode\AI\tools\team-video-library-browser
```

## Quick Start

Run the local MVP:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\process_location.ps1" `
  -InputDir "D:\Download\素材下载\两天一夜团建 浙江\视频作品\千岛湖"
```

Equivalent direct command:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" process-location `
  "D:\Download\素材下载\两天一夜团建 浙江\视频作品\千岛湖" `
  --orientation vertical --detector adaptive --split-mode accurate
```

Default output is created next to the input folder:

```text
千岛湖/
千岛湖智能镜头分类/
```

## Workflow

This skill has two connected loops:

- `地点原视频采集`: from a large downloaded source library, collect raw videos for one location.
- `地点素材建库`: clean downloaded location videos into reusable silent shots.
- `参考成片学习`: analyze downloaded finished/source videos to learn transcript-to-visual mapping, rhythm, and editing rules before making new cuts.
- `素材需求雷达`: read/transcribe raw source videos, extract concrete material keywords, compare them with the existing library, and list what the user should collect next.
- `文案智能配镜`: read a script, match library clips, copy them into a numbered folder for Jianying import.

Read `references/content-production-line.md` when the user talks about the larger content workflow.
Read `references\ai-dependent-workflow-policy.md` before adding buttons, local tools, or "automation" for any workflow step that may need visual/semantic AI judgment.
Read `references\open-source-upgrade-map.md` before adding new video-editing capabilities or installing new tools; reuse proven open-source libraries where they fit.
Read `references\modular-skill-architecture.md` when the user asks whether to split this into multiple skills or when a module starts getting too large.
Read `references\feishu-review-board.md` when the user wants to review script-to-shot matching, rough cuts, or storyboard decisions in Feishu/Lark.
Read `references\reference-video-learning.md` when the user asks whether to learn from original/reference videos before editing, or wants statistics and rules for "what line maps to what picture".

## Reference-Audio Recompose Workflow

Use this when the user says the original/source video already has the desired style, copy, rhythm, or audio, and they only want the visuals replaced.

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\recompose_reference.ps1" `
  -ReferenceVideo "D:\path\to\参考视频.mp4" `
  -LibraryRoot "D:\Download\素材下载\团建视频\安吉智能镜头分类" `
  -Title "安吉夏季玩水团建_换画面粗剪"
```

Direct command:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" recompose-reference `
  "D:\path\to\参考视频.mp4" `
  "D:\Download\素材下载\团建视频\安吉智能镜头分类" `
  --title "安吉夏季玩水团建_换画面粗剪"
```

Rules:

1. Reference video may be horizontal or vertical; its audio/script/rhythm can be used.
2. Final visual track is rendered as 9:16 vertical by default.
3. Prefer same-stem `.txt` next to the reference video as the script. If no `.txt` exists, use `--transcribe` when transcription is needed.
4. Source library clips are not modified; selected clips are copied to `jianying_pack`.
5. Output includes `rough_cut.mp4`, `reference_audio.m4a`, `script.txt`, `recompose_plan.csv`, `summary.json`, and `jianying_pack`.
6. For public posting, treat other people's original audio as a reference or draft unless the user confirms it is safe to publish.

Check the output folder before reporting completion:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\check_delivery.ps1" `
  -OutputDir "D:\path\to\本次输出"
```

This writes `delivery_check.json` and `delivery_check.md`, and fails if the rough cut is missing, not vertical, silent, or the Jianying pack is empty.

## Audio Material Library Workflow

Use this before script-to-shot matching when the user says the audio should come from original source videos, not from split scene clips.

Source rule:

```text
<地点>-原视频素材 -> <地点>音频素材库
```

Default command:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" extract-audio-library `
  "D:\Download\素材下载\团建视频\<地点>-原视频素材" `
  --transcribe
```

Fast command when same-stem `.txt` sidecars already exist:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" extract-audio-library `
  "D:\Download\素材下载\团建视频\<地点>-原视频素材"
```

Output:

- `<地点>音频素材库\001_<原视频名>.m4a`
- `<地点>音频素材库\001_<原视频名>.txt`
- `<地点>音频素材库\音频素材库清单.csv`
- `<地点>音频素材库\音频素材库清单.json`

Rules:

1. Audio is extracted from unsplit original source videos only, not from silent scene clips.
2. If a same-stem `.txt` exists next to the source video, copy it into the audio library with the same stem as the `.m4a`.
3. If no text exists and `--transcribe` is provided, transcribe the source audio with timecodes.
4. If neither text nor transcription is available, write a `# 待转写` placeholder; comment lines must not be treated as script beats.
5. The audio library is the narration/script source for matching and rough cutting. The scene library is the visual source.
6. Do not modify or move the original source videos while extracting audio.

Preferred shot-match route:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" build-match-board `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类" `
  --title "<视频标题>" `
  --audio-file "D:\Download\素材下载\团建视频\<地点>音频素材库\001_<原视频名>.m4a"
```

This reads the same-stem `.txt` next to the `.m4a` as the script:

```text
原视频音频 -> 台词时间线 -> 智能镜头匹配 -> 审片板 -> 粗剪
```

Render a rough cut from an accepted board:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" build-rough-cut-from-board `
  "<审片板输出>\智能镜头匹配候选表.json" `
  --audio-file "D:\Download\素材下载\团建视频\<地点>音频素材库\001_<原视频名>.m4a"
```

The rough-cut step writes `visual_track.mp4`, `rough_cut.mp4` when audio is available, `jianying_pack`, `rough_cut_plan.csv`, and `summary.json`.

Current smart-match MVP outputs:

1. `智能镜头匹配审片板.html`: left transcript, middle AI visual need and candidate thumbnails, right notes.
2. `智能镜头匹配审片表.xlsx`: Excel review board with transcript, AI visual need, up to 5 candidate thumbnails, reasons, confirmation columns, and notes.
3. `智能镜头匹配候选表.csv/json`: machine records for every line and candidate.
4. `智能镜头匹配审片反馈表.csv/json`: blank feedback table with confirmation result, selected candidate, replacement keyword, and note.
5. The HTML board saves notes in browser localStorage and can export `智能镜头匹配审片反馈.json`.
6. `build-rough-cut-from-board` renders `rough_cut.mp4`, `visual_track.mp4`, `jianying_pack`, `rough_cut_plan.csv`, and `script.txt`.
7. `build-rough-cut-from-board --feedback-file <反馈表.csv|json>` must apply review feedback: selected candidate ranks are preferred; rows marked `废料`, `跳过`, `不用`, `删除`, or `不需要` are skipped.
8. `check-delivery` must recognize `rough_cut_plan.csv` and report no warnings when the rough cut is vertical, has audio, has script text, and has enough pack clips.
9. The local browser UI scans `成品粗剪` outputs from `_自动粗剪*`, `*成品区`, and `*智能匹配工作流` folders so the user can preview rough cuts, visual tracks, Jianying packs, and workflow segments in the 成品检查 stage.

One-command smart-match production route:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" smart-match-workflow `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类" `
  --title "<视频标题>"
```

Default audio rule:

1. If `--audio-file` is provided, use that exact audio and its same-stem `.txt`.
2. Otherwise, use the same-level `<地点>音频素材库` next to `<地点>智能镜头分类`.
3. If the audio library does not exist, build it from the same-level `<地点>-原视频素材`, then reuse it on later runs.
4. The audio library is a persistent location asset, not a temporary folder inside one rough-cut output.

List selectable audio files before matching:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" list-audio-library `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类"
```

Choose a specific narration:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" smart-match-workflow `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类" `
  --title "<视频标题>" `
  --audio-index 19

python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" smart-match-workflow `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类" `
  --title "<视频标题>" `
  --audio-query "小橘"
```

This creates:

- `01_审片板`: HTML board, Excel board, candidate CSV/JSON, feedback CSV/JSON, thumbnail images.
- `02_粗剪成品`: `rough_cut.mp4`, `visual_track.mp4`, `jianying_pack`, `rough_cut_plan.csv`, `script.txt`, delivery check.
- `smart_match_workflow_summary.json`: one file connecting the audio, board, rough cut, and delivery status.

Use feedback-driven rerender:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" smart-match-workflow `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类" `
  --title "<视频标题>_按反馈重出" `
  --audio-file "<音频.m4a>" `
  --feedback-file "<审片反馈表.csv>"
```

Feedback rules:

1. `通过` keeps the current matching result.
2. `选中素材序号` prefers that candidate during rough-cut rendering.
3. `废料`, `跳过`, `不用`, `删除`, or `不需要` skips that script beat during rough-cut rendering.
4. `替换关键词` or `重配关键词` is applied before candidate search: the board is regenerated with the new visual target, then the rough cut is rendered from those refreshed candidates.
5. The spoken script line stays unchanged; only the visual need and candidate search target are changed.

The workflow should be the default path when the user asks for "文案智能匹配素材", "按音频节点配画面", "一键粗剪", or "先出审片表再出成品".

## Reference Video Learning Workflow

Use this before serious rough-cut generation when the user wants Codex to learn from downloaded finished videos or original source videos.

Goal:

```text
reference video -> transcript beats -> shot/keyframe analysis -> line-to-visual mapping -> reusable editing rules
```

Default source folders:

```text
D:\Download\素材下载\团建视频\千岛湖-原视频素材
D:\Download\素材下载\团建视频\安吉-原视频素材
D:\Download\素材下载\团建视频\莫干山-原视频素材
```

Default command:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" learn-reference-videos `
  --max-videos 4 `
  --max-beats-per-video 8 `
  --orientation vertical `
  --transcribe-audio `
  --publish-feishu true
```

Rules:

1. Prioritize vertical finished/reference videos. Horizontal videos can be learned for script/audio rhythm but should not become preferred visual style.
2. For line-to-picture learning, prefer audio transcription with timecodes (`--transcribe-audio`). Same-stem `.txt` files are copy/script supplements, not timing truth.
3. Split the video into shots, extract representative frames, and align each spoken beat to the visible shot time range.
4. For each beat, record: spoken line, time range, visible picture, concrete keyword, category, shot role, selected frame, source video path, and why the visual matches.
5. Produce statistics: common line-to-picture pairs, shot length distribution, clips per sentence, direct-match ratio, B-roll/transition ratio, opening hooks, climax patterns, and ending patterns.
6. Store results locally under `00-模板库\参考成片学习库` and publish reviewable summaries to the Feishu review board when useful.
7. The learning layer improves matching rules; it does not move or overwrite source videos.
8. Reject pseudo-vertical videos whose middle keyframe shows obvious horizontal footage inside vertical black bars.
9. If ASR text is fuzzy or the line has no concrete keyword, mark the row as `待画面复核`; do not invent a folder from the title.

## Low-Touch Production Workflow

Use this mode when the user wants to reduce manual participation and says they will only add new materials or provide a script.

Default local folders:

```text
D:\Download\素材下载\团建视频\00-待分类整理库
D:\Download\素材下载\团建视频\00-模板库
D:\Download\素材下载\团建视频\<地点>-原视频素材
D:\Download\素材下载\团建视频\<地点>智能镜头分类
```

Default one-command wrapper:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\simple_workflow.ps1" `
  -Location "安吉"
```

When the user also provides a script, build an edit pack after updating the location library:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\simple_workflow.ps1" `
  -Location "安吉" `
  -ScriptFile "D:\path\to\文案.txt" `
  -Title "安吉夏季玩水团建"
```

Operating policy:

1. The user only drops new videos into `00-待分类整理库` and names the location/theme.
2. Collect matching source videos and sidecar text first, then clean duplicate and mixed-location source videos.
3. Build or update the location scene library with visual-first classification.
4. Keep narration/transcript as evidence for matching, not as the final physical folder decision.
5. Use `00-模板库` for script rewriting, video structure, and keyword-to-shot matching.
6. Copy selected clips into a numbered `智能配镜` folder; never move clips out of the reusable library.
7. For TTS or original-audio workflows, transcribe source audio and align source timecodes to clips before matching script beats.
8. Follow `D:\Download\素材下载\团建视频\00-模板库\配镜规则\语音转文字与配镜.md`: transcript helps matching, but the visible picture decides the reusable library folder.

## Material Demand Radar Workflow

Use this when the user says they need to extract all video copy/audio, find what material keywords are missing, or batch collect new source footage before editing.

Default command:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" analyze-material-demand `
  --source-root "D:\Download\素材下载\团建视频" `
  --locations 安吉 千岛湖 莫干山 `
  --publish-feishu true
```

Rules:

1. Prefer same-stem `.txt` files for fast first-pass demand extraction; use `--transcribe-audio` or `--force-transcribe` when audio text is missing or must be verified.
2. Extract concrete collection keywords, such as `莫干山烤全羊`, `莫干山烧烤`, `莫干山民宿`, `莫干山露营`, `安吉漂流`, or `千岛湖皮划艇`.
3. Compare keyword demand with the existing `地点智能镜头分类` library and mark each item as `缺素材`, `偏少`, or `够用`.
4. Publish a Feishu-readable demand board with location, keyword, gap, demand hits, existing estimate, suggested search terms, and reason.
5. Do not show local paths in the Feishu demand board. Keep full paths and complete transcripts in local CSV/JSON only.
6. Treat this as the collection step before scene splitting and rough-cut generation.

## Location Source Collection Workflow

Before splitting scenes, collect raw videos from the large downloaded source library:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\collect_location_sources.ps1" `
  -SourceRoot "D:\Download\素材下载\江湖采集库" `
  -OutputRoot "D:\Download\素材下载\团建视频" `
  -Location "千岛湖" `
  -Move:$true
```

Rules:

1. Match the location keyword only in the title text before the first `#` or `＃`.
2. Ignore keywords that appear only in hashtag topics, such as `#千岛湖团建`.
3. Exclude mixed collection/list videos when the pre-hashtag title contains multiple locations or collection wording such as `合集`, `8大`, `目的地`, `整理好了`, or `照抄`.
4. Move the video and same-stem sidecar files such as `.txt` into `地点-原视频素材`.
5. If the same video already exists in `地点-原视频素材`, delete that duplicate and its same-stem sidecar files directly from `江湖采集库`; do not create `采集库已整理重复源` folders.
6. Clean existing `地点-原视频素材` folders by quarantining mixed/list videos, hashtag-only mistakes, and exact duplicate source videos under `._采集记录`.
7. Write the selection and cleanup reports under `D:\Download\素材下载\团建视频\._采集记录`.

Direct command:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" collect-location-sources `
  "D:\Download\素材下载\江湖采集库" `
  --output-root "D:\Download\素材下载\团建视频" `
  --location "千岛湖" `
  --move true
```

Clean an existing raw source folder:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" clean-location-sources `
  --output-root "D:\Download\素材下载\团建视频" `
  --location "千岛湖" `
  --move true
```

## Location Library Workflow

1. Treat the input as one location folder, not one video.
2. Scan common video formats recursively: mp4, mov, mkv, avi, m4v, webm.
3. Keep vertical/portrait videos by default; record horizontal videos as skipped.
4. Do not modify, move, overwrite, or delete original videos.
5. Detect shot boundaries before classification.
6. Split each shot into a standalone MP4 and remove audio with FFmpeg `-an`.
7. Extract 20%, 50%, and 80% keyframes for review and later AI classification.
8. Route each clip to exactly one physical category folder.
9. Write secondary attributes only to CSV/JSON/SQLite; never copy a clip into multiple folders.
10. Record source video, source path, source timecode, scene id, output path, quality, and classification.
11. On rerun, use the record store to skip unchanged processed videos unless force reprocess is requested.

## Script-To-Shot Workflow

After a location library exists, create a numbered edit pack:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" build-edit-pack `
  "D:\Download\素材下载\两天一夜团建 浙江\视频作品\千岛湖智能镜头分类" `
  --title "千岛湖夏季团建" `
  --script-file "D:\path\to\文案.txt"
```

The pack contains numbered clips, `文案.txt`, `配镜表.csv`, and `配镜说明.md`.

For matching rules, read `references/script-to-shot-policy.md`.

## Smart Shot Match Board Workflow

Use this before rough cutting when the user wants `素材文案智能匹配`, `智能镜头匹配`, `台词右边放素材截图`, `先做审片表`, or wants to confirm which visuals match each spoken line.

Default command:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" build-match-board `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类" `
  --title "<视频标题>" `
  --script-file "<带时间戳的台词.txt>"
```

Reference-video mode can also extract the source audio into the output folder:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" build-match-board `
  "D:\Download\素材下载\团建视频\<地点>智能镜头分类" `
  --title "<视频标题>" `
  --reference-video "<原视频.mp4>"
```

Output:

- `智能镜头匹配审片板.html`: local review board. Left is transcript/script beat, middle is up to 5 candidate material screenshots, right is a note box.
- `智能镜头匹配候选表.csv`: machine-readable and spreadsheet-readable match table.
- `智能镜头匹配候选表.json`: full trace with candidate paths, reasons, and scores.
- `候选截图/`: extracted keyframes for quick review.
- `音频提取/`: created when `--reference-video` is provided.

Rules:

1. This is the required review layer between clean material preprocessing and rough cutting.
2. Timecoded transcript text should preserve `start/end/text`; if no timestamps are present, split the script into natural beats.
3. Columns must keep the user's review shape: `字幕台词 -> AI画面需求 -> 素材1..素材5截图/理由 -> 备注`.
4. Prefer the current physical folder and filename as the strongest evidence. Historical JSON/SQLite tags are weak evidence because the user may have manually corrected folders.
5. Concrete activity beats must not be filled with wrong activity clips. `皮划艇` cannot use `骑行`, `吃饭`, `大巴`, or generic `项目活动` as a direct match.
6. If at least one direct match exists, support shots may be added after it: environment, team interaction, people reaction, or detail shots.
7. If no direct material exists, mark the row `缺直接素材`; do not pretend it is matched.
8. Do not copy or move source clips in this step. Copy numbered clips only after the review board is accepted.

## Feishu Review Board Workflow

Use this when the user wants the review surface in Feishu rather than local CSV/Excel/HTML.

Current default review doc:

```text
https://my.feishu.cn/docx/YanldTyg5oPwgnxahn6cCIGdnbd
```

Rules:

1. Use `lark-cli` with `--as user` for documents the user owns or needs to edit.
2. If a shared reference document is readable but not editable, create a new user-owned document instead of blocking.
3. The review board rows should include: `镜号`, `台词/文案`, `关键词/画面需求`, `素材截图/画面`, `选择理由`, and `状态`.
4. Feishu tables must show screenshots/images directly inside table cells. Do not show local clip paths or screenshot paths in Feishu tables unless the user explicitly asks for them.
5. Prefer uploading representative keyframes or compact contact-sheet thumbnails, not full videos, to keep Feishu review fast.
6. Keep local CSV/JSON as machine records, but treat Feishu as the user's primary review and correction surface.
7. When the user marks a row as `通过`, `替换`, `废料`, or `重配`, pull that feedback back into the local correction records before rerendering.

## Keyword Refinement Workflow

After the first library exists, run keyword refinement before judging the library quality. This is the user's core editing goal: script keywords should find direct matching visuals.

Important: keyword/transcript routing is only a draft signal. It must not be treated as final visual classification when the narration and picture may disagree.

Visual-first policy:

1. The physical folder must describe the visible picture, not only the narration.
2. Transcript, source `.txt`, and filename are secondary evidence and can be wrong when a Douyin voiceover spans many shots.
3. If keyword routing moves a whole source sequence into one category, run visual audit contact sheets before accepting the folder.
4. When the user reports several wrong clips in one folder, audit the whole folder/source sequence, apply visual corrections, and lock corrected records.
5. Use `apply-visual-corrections` to move corrected clips, update `project.sqlite`, `scenes.csv`, `project.json`, write a visual correction report, and rename clips.

2026-06-25 production learning:

1. For existing messy libraries, do not repair only the reported clips. Generate full-library contact sheets and apply a full visual correction CSV for every clip.
2. The correction CSV should use stable ASCII short codes for categories/keywords, then map them to Chinese folder names in `visual_corrector.py`. This avoids Windows/PowerShell Chinese path encoding accidents.
3. Keep `口播讲解`, `方案讲解`, `图文海报`, `行程表截图`, and similar explainer/poster clips under `90_待人工分类`; do not mix them into clean b-roll folders.
4. For team-building editing, concrete visual keywords are more useful than broad folders. Prefer `漂流`, `皮划艇`, `游艇游湖`, `真人CS`, `山地越野车`, `彩虹滑道`, `高山滑道`, `高空滑索`, `射箭`, `麻将`, `KTV唱歌`, `剧本杀`, `农家菜`, `烧烤`, `烤全羊`, `篝火`, `烟花`, `民宿房间`, `莫干山风景`, `千岛湖风景俯拍`, etc.
5. After applying visual corrections, run a self-check: on-disk clip count, `processing_status='written'`, `manual_locked=true`, missing output records, and folder distribution.

1. Transcribe original source videos with timecodes.
2. For each silent clip, map its source start/end time to nearby transcript text.
3. Combine transcript text, source sidecar text, filename, existing tags, and optional OCR.
4. Move the clip into a concrete keyword subfolder, for example `02_出发抵达/大巴集合出发`, `04_餐饮美食/千岛湖鱼宴`, `05_项目活动/皮划艇`, or `07_烧烤露营夜场/烧烤`.
5. Keep each clip in one physical folder only; write all extra evidence to `._系统记录/keyword_refine.csv`.
6. Run with `-Move:$false` first when previewing a new ruleset; run with `-Move:$true` when applying.

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\transcribe_sources.ps1" `
  -LibraryRoot "D:\Download\素材下载\两天一夜团建 浙江\视频作品\千岛湖智能镜头分类" `
  -Model tiny -Language zh

& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\refine_keywords.ps1" `
  -LibraryRoot "D:\Download\素材下载\两天一夜团建 浙江\视频作品\千岛湖智能镜头分类" `
  -Ocr:$false -Transcript:$true -Move:$true
```

## Category Policy

Use the fused v1 category layout:

```text
01_环境空镜
02_出发抵达
03_住宿空间
04_餐饮美食
05_项目活动
06_团队互动
07_烧烤露营夜场
08_人物反应
09_细节特写
10_收尾返程
90_待人工分类
._系统记录
```

Only create dynamic subfolders under `05_项目活动` when a project is actually detected, such as `皮划艇`, `游艇游湖`, `水上拓展`, `湖边骑行`, `真人CS`, or `漂流`.

Never create these folders in the user-facing library: `全部镜头`, `精选素材`, `可用素材`, `重复镜头`, `低质量镜头`, `剪辑功能`, or per-source-video output folders.

Read `references/classification-policy.md` before changing categories or classification behavior.

## Current MVP Behavior

- Uses FFmpeg and PySceneDetect/OpenCV.
- Uses metadata, transcript timecodes, and keyword routing as the first concrete classifier.
- Records exact duplicate source videos and skips duplicate sources by default.
- Does not yet perform full OpenCLIP visual classification or near-duplicate clip clustering.
- Saves reports under `._系统记录`: `source_videos.csv`, `scenes.csv`, `project.json`, `run_summary.json`, `processing_report.md`, `keyword_refine.csv`, `project.sqlite`.

## Common Commands

Open the local material library browser:

```powershell
& "D:\AICode\AI\tools\team-video-library-browser\启动素材库浏览器.bat"
```

It scans only `*-原视频素材` and `*智能镜头分类`; it does not upload, copy, or modify source materials.

Browser UI material operations:

1. In `素材整理`, every material card supports right-click and the bottom-right `...` menu.
2. Menu actions include `重命名素材`, `添加标签`, `裁切废料`, `打开文件夹`, `复制路径`, and `移到回收站`.
3. The right preview panel has one combined `裁切废料` button. It opens a single panel where the upper area controls picture crop and the lower timeline controls start/end time trim.
4. The combined panel auto-detects the subtitle line when opened, defaults to skipping the first frame by starting at about 0.05 seconds, and outputs with `输出新素材`. A second button, `输出新素材并删除原素材`, must first create the new clip successfully, then move the original source clip and sidecars to the Windows Recycle Bin.
5. Saved crop layouts can be applied, saved, and deleted.
6. Manual crop and time trim output new copies under a `手动处理` subfolder next to the source file; they never overwrite the original.
7. `移到回收站` sends the file and same-stem sidecars to the Windows Recycle Bin, so accidental deletion is recoverable from the system recycle bin.
8. Tags are local browser metadata stored under `00-模板库\素材库浏览器缓存\tags.json`; tags help searching but do not move the physical file.
9. Browser filters must flow from parent to child: `素材类型 -> 地点 -> 一级分类 -> 具体场景/素材组`. Child selections must not collapse parent dropdowns into a single option.
10. `素材整理` 顶部只有一个 `批量处理` 入口；其中 `提取音频/文案素材` scans `已整理原片`, extracts `.m4a` into `D:\Download\素材下载\团建视频\已整理原片音频\<地点>\`, optionally generates same-stem transcript/timecode text, skips existing outputs, and keeps original videos unchanged. `批量转写文案` should generate both `.transcript.txt` with timestamps for editing and `.plain.txt` plain copy for writing.
11. The browser scans both `已整理原片音频` and existing `<地点>音频素材库` folders as `原片音频素材`.
12. In `智能剪辑`, do not build a fake local editing timeline. The local browser is a preview/selection surface only: left side shows the audio selector and the initial matched material thumbnails for that audio; right side previews the selected clip and exposes only `合并播放`, `裁剪切割`, and `打开素材包`.
13. `复制配镜提示词` is the entry for real smart matching. Codex reads the selected audio transcript or ASR cache, splits transcript beats, performs semantic/keyword/scene matching against `分镜素材`, then creates a numbered `智能剪辑初剪库\<日期时间_音频标题>` material pack for Jianying import. The material pack must keep clips as separate numbered files in one folder; do not force-concatenate them into a single video when the user asks for simple materials, because Jianying should import them as individually editable clips. The browser may show a rough local candidate preview, but final matching quality belongs to the Skill workflow and must be self-checked.
14. `素材整理` must show a compact workflow dashboard: `总控任务队列`, `素材初加工闭环`, and `任务结果面板`. The dashboard should auto-poll `/api/batch-progress` without manual refresh, animate the queue dot while running, expose current item/progress/success/skipped/failed counts, and keep a direct `批量处理` entry. Count chips should show only number + label; longer notes belong in hover titles or detail panels. Do not duplicate the same count chips in the header.

## Material Preprocessing Crop Module

Use this module when the user says `裁切废料`, `裁去字幕`, `裁剪`, `字幕之上`, `素材初加工`, `去掉底部字幕`, or wants downloaded source videos cleaned before scene splitting.

Workflow:

1. Do not overwrite the original video.
2. Sample about three representative frames from the source video.
3. Detect the top edge of the visible lower subtitle/text band. The retained crop should end just above that detected text.
4. If automatic detection is uncertain, open the browser UI crop editor and let the user adjust a draggable/resizable crop frame.
5. Save reusable crop layouts for repeated source/channel styles, such as `裁切废料_74`, `裁去字幕_72`, or `小红书底部字幕_72`.
6. Generate a new video copy under a `手动处理` or `裁切废料` subfolder next to the source file.
7. After crop cleanup, run scene splitting and visual-first keyword classification only on the cleaned version when it is materially better than the source.
8. If a clip has center title cards, large stickers, poster text, or non-material explanation graphics, treat that segment as waste material instead of trying to keep it.
9. If large cover/title graphics appear only for a few seconds inside an otherwise useful clip, cut out that time segment and concatenate the remaining useful sections instead of discarding the whole clip.
10. For `裁切废料`, do not skip the beginning just because one or two frames contain lower-third subtitles. Only suggest an intro time cut when large cover/title waste is detected across several consecutive opening frames.
11. Some Douyin team-building clips place yellow/white subtitles around the lower third rather than the bottom edge. If a light bottom crop still leaves subtitle text, create a stronger `字幕之上` test copy and visually compare it before accepting the result.
12. All material preprocessing outputs should default to trimming the first frame or tiny cover-frame lead-in, about `0.08s` for 30fps videos. This removes single-frame covers without cutting meaningful action. Only keep time zero when the user explicitly says the first frame must be preserved.
13. The current preferred batch route for already-organized original videos is `scripts/crop_waste_and_split_originals.py`: detect crop/time-waste on `<地点>-原视频素材`, split directly from the source video, and write new silent clips into the existing `<地点>智能镜头分类` folders with `裁剪分割` in the filename. Do not create a separate island library when the user wants side-by-side comparison.
14. Treat obvious pseudo-vertical footage with large black bars as a crop-first case, not an automatic discard. Before splitting, sample frames and measure top/bottom black bars; if the average black bars are roughly 24%+ of the frame or any sampled frame is roughly 32%+, crop the black bars and then intersect that crop with the subtitle/top-watermark crop. Only skip when the remaining useful picture is too small or still visibly dirty.
15. Batch execution is rule-based and fast. Quality review is a separate Codex/visual-AI layer: inspect contact sheets, tighten rules when subtitles/title cards remain, then rerun incrementally.
16. For source batches with higher subtitle placement, prefer a stronger subtitle safety line over preserving every bottom pixel. Current verified rule uses an 18% safety margin above detected subtitle text. If subtitles remain after that, stop crop-only retries and send the clips to deep subtitle/watermark removal or manual review.
17. Scene-level waste cutting must happen before output: extract a representative frame from each scene and skip scenes with large title cards, poster/sticker overlays, or obvious non-material text blocks. Record these as `skip_title_overlay` instead of writing them into the clean comparison library.
18. Do not crop a clip just because the detector sees weak low-score text-like edges. If no reliable bottom subtitle is detected, return full-frame/no-crop and let the batch skip it. This prevents clean clips such as bus boarding or indoor signs from being wrongly cropped.
19. Distinguish small channel watermarks from bottom subtitles. Small low-contrast watermarks such as `千岛湖阿明`, Douyin, or Xiaohongshu marks should go to a tested deep-repair workflow; do not solve them by bottom-cropping if the useful frame is otherwise clean.

Browser UI support:

- Right preview panel button `裁切废料` opens the crop editor.
- `自动检测字幕线` calls the local frame-based detector and sets the crop frame.
- The crop editor must show the full video frame with `object-fit: contain`; do not let the modal height hide the lower subtitle area during review.
- Automatic subtitle-above detection should keep a safety margin above the detected subtitle top. If the preview still shows half subtitles, prefer cropping slightly higher rather than preserving the last few pixels at the bottom.
- In `裁切废料` mode, keep the useful picture by default. Remove top/bottom subtitle bands through crop, and remove large center cover/title cards through time cutting. Never treat a center title card or lower-third title as a top watermark.
- `保存当前布局` stores the current crop rectangle in `00-模板库\素材库浏览器缓存\crop_layouts.json`.
- `当前工作流提示词` in the left sidebar is a portable prompt for handing this preprocessing workflow to other local AI tools.

Dry-run only:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" process-location "<地点文件夹>" --orientation vertical --dry-run
```

Process all selected vertical videos:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" process-location "<地点文件夹>" --orientation vertical --detector adaptive --split-mode accurate
```

Force a clean rerun:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" process-location "<地点文件夹>" --orientation vertical --force-reprocess
```

Transcribe source audio:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" transcribe-sources "<智能镜头分类文件夹>" --model tiny --language zh
```

Apply keyword subfolder refinement:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" refine-keywords "<智能镜头分类文件夹>" --ocr false --transcript true --move true
```

Apply visual review corrections:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" apply-visual-corrections `
  "<智能镜头分类文件夹>" `
  --corrections-csv "<视觉纠错表.csv>" `
  --report-name "visual_corrections_<范围>.csv"
```

Generate visual audit contact sheets before correcting a messy library:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\visual_audit.ps1" `
  -LibraryRoot "<智能镜头分类文件夹>" `
  -GroupBy folder `
  -ClipsPerSheet 12
```

This writes review images and a correction template under:

```text
<智能镜头分类文件夹>\._系统记录\visual_audit\
```

Use this when a folder looks polluted, such as 大巴集合出发 containing aerial scenery, food, or kayaking. The review step does not move clips. After filling `visual_audit_corrections_template.csv`, apply it with `apply-visual-corrections`.

Create a non-destructive clean-material copy:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\clean_materials.ps1" `
  -InputDir "<智能镜头分类文件夹>" `
  -OutputDir "<干净素材副本文件夹>" `
  -Mode crop-bottom `
  -BottomPct 0.28
```

Rules:

1. Never overwrite the original library.
2. First-stage cleanup uses `crop-bottom` to remove common lower hard-subtitle areas.
3. This is a safe baseline copy, not the final AI inpainting solution.
4. If the subtitle is centered or high on the screen, use the next AI-inpainting module instead of over-cropping the footage.
5. Do not crop clips that do not visibly need cropping. First split or sample frames, then decide per clip: keep original size, crop a small edge watermark, or discard the clip.
6. Middle title cards, large stickers, poster text, and graphic explainer frames are waste material for b-roll libraries; remove those clip segments instead of trying to crop or repair them.

Self-checked clean replacement workflow:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" clean-materials `
  "<鏅鸿兘闀滃ご鍒嗙被鏂囦欢澶?" `
  --output-root "<鑷娓呮礂鐗? `
  --mode adaptive-crop

python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" select-clean-materials `
  "<鑷娓呮礂鐗? `
  --output-root "<鏈€缁堝彲鐢ㄥ共鍑€鏇夸唬鐗? `
  --threshold 0.08
```

Rules:

1. `adaptive-crop` estimates lower text residue and chooses a conservative bottom crop.
2. `select-clean-materials` keeps low-residue clips and replaces high-residue clips with low-residue alternatives from the same folder, same top-level category, then global fallback.
3. This produces a usable edit library when perfect per-clip subtitle removal is not yet available.
4. Do not describe this as perfect original-clip inpainting. True hard-subtitle/watermark removal should use the `video-subtitle-remover` deep inpainting environment after it is installed and tested.
5. Always self-check top-level folder counts, root-level loose mp4 count, kept/replaced counts, and pending repair count before reporting completion.

Subtitle/watermark cleanliness audit:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" audit-overlays `
  "<素材库目录>" `
  --output "<字幕水印审计输出目录>"
```

Rules:

1. A "clean library" must pass overlay audit; crop-only or blur-only output is not enough.
2. Preserve original dimensions for true subtitle/watermark removal unless the user explicitly asks for crop.
3. Copy only `label=clean` clips into the no-subtitle/no-watermark candidate library.
4. Put `bottom_subtitle`, `top_watermark`, `center_overlay`, and `mixed_overlay` clips into a deep-repair queue.
5. Do not batch-accept OpenCV inpainting results when the repaired sky, faces, hands, food, or action areas look blocky.
6. Read `references/subtitle-watermark-cleaning-policy.md` before reporting a material library as clean.

VSR hard-subtitle cleaning pilot:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\vsr_clean.ps1" `
  -InputVideo "<input.mp4>" `
  -OutputVideo "<output.mp4>" `
  -Mode opencv `
  -YMin 900 -YMax 1920 -XMin 0 -XMax 1080
```

Rules:

1. Use VSR for actual hard subtitles or watermark text that OCR can detect.
2. Do not rely on VSR alone for graphical stickers, poster text, or title-card overlays. If VSR reports no subtitle detected, mark the clip as visually dirty or handle it with separate mask/inpaint rules.
3. The local VSR environment is at `D:\AICode\AI\tools\external-video-reference\video-subtitle-remover\.venv`.
4. Current verified state: CLI starts, dependencies are installed, and the wrapper is available; graphical text detection still needs a separate workflow.

Rename clips after keyword refinement:

```powershell
python "D:\AICode\AI\tools\teambuilding-video-scene-library\main.py" rename-clips "<智能镜头分类文件夹>" --move true
```

The readable final filename format is:

```text
001_地点_具体关键词__质量_来源视频ID_镜头ID.mp4
```

After renaming, update `project.sqlite`, `scenes.csv`, `project.json`, and write `clip_rename.csv`.

## Deep Repair Mode

Use `深度修复模式` as the second material-cleaning path after `裁剪模式`.

Material cleaning has two modes:

1. `裁剪模式`: rule-based crop/time cutting. Use it for bottom subtitles, black bars, one-frame covers, title-card lead-ins, and obvious waste segments. It is fast and suitable for batch preprocessing, but it changes the usable frame area.
2. `深度修复模式`: VSR/AI inpainting. Use it when the picture is valuable and the subtitle/watermark should be removed while preserving the original frame size. It is slower, must be tested on a small sample first, and requires before/after visual review before batch acceptance.

Decision rule:

1. Use `裁剪模式` first when the subtitle area can be removed without damaging the shot's editing value.
2. Use `深度修复模式` when cropping would lose important action, people, food, scenery, or project details.
3. Do not use deep repair for large cover pages, poster text, stickers, or full-screen title cards. Cut those as waste material.
4. Prefer `sttn-auto` for candidate clean output. `opencv` is only a fast prototype because it often leaves blocky or blurry repair bands.
5. Always generate before/after or before/opencv/sttn comparison images for review.
6. Do not call any repaired clip "clean" until it has a before/after contact sheet and a second visual check.
7. Silent scene clips may trigger audio-merge warnings inside VSR; accept the repaired video only when the visual output exists and plays. For original videos with audio, preserve or reattach audio in a separate post-step.
8. Low-contrast sky watermarks may not be removed by VSR detection even when the area is provided. Test one clip first; if the watermark only becomes lighter or leaves a rectangular smear, mark the method as not batch-ready and keep the original/crop path instead.

Default command:

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\vsr_clean.ps1" `
  -InputVideo "<input.mp4>" `
  -OutputVideo "<output.mp4>" `
  -Mode sttn-auto `
  -YMin 900 -YMax 1920 -XMin 0 -XMax 1080
```

Current verified state on 2026-07-03:

- Local VSR environment: `D:\AICode\AI\tools\external-video-reference\video-subtitle-remover\.venv`
- Test artifact folder: `D:\Download\素材下载\团建视频\._采集记录\VSR_AI去字测试_20260703`
- Test clip: 720x860 / 0.9s / 27 frames, Anji drift hard subtitle.
- `opencv`: about 158s, removed text but left obvious blur.
- `sttn-auto`: about 97s, visually better but still has slight repair traces.

## References

- `references/classification-policy.md`: category priority and examples.
- `references/quality-policy.md`: quality levels and low-quality handling.
- `references/naming-policy.md`: output naming and traceability.
- `references/review-corrections.md`: how to treat manual corrections.
- `references/content-production-line.md`: the full semi-automatic production line.
- `references/script-to-shot-policy.md`: how to match scripts to library clips.
- `references/jianghu-toolbox-integration.md`: discovered local Jianghu Toolbox path and safe integration policy.
- `references/open-source-upgrade-map.md`: open-source tools and public project patterns to learn from before building new features.
- `references/modular-skill-architecture.md`: how to split the workflow into smaller modules or future skills.
- `references/module-status.md`: current implemented modules, checks, and the next best module.
- `references/todo.md`: live task list for this video workflow.
- `references/subtitle-watermark-cleaning-policy.md`: true clean-material policy for subtitle/watermark audit, deep repair, and second-pass checks.
- `references/deep-repair-mode.md`: the VSR/AI inpainting workflow for `深度修复模式`, including the 2026-07-03 sample result and batch acceptance rules.
- `prompts/classify-clip.md`: structure for future keyframe-based AI classification.
- `prompts/match-script-beat.md`: structure for future semantic script-to-shot matching.

## 2026-07-03 Horizontal Scene Clip Policy

User-confirmed rule update:

1. Any horizontal video already inside a `*智能镜头分类` scene library is not considered reusable editing material.
2. Horizontal scene clips in the smart scene library should be removed from the user-facing library, preferably to Windows Recycle Bin with a deletion log.
3. Do not delete or overwrite `*-原视频素材` original source videos.
4. Horizontal original videos should be evaluated through `深度修复模式` instead of crop-based scene outputs.
5. After deleting horizontal scene clips, run a full rescan/self-check and confirm that horizontal clips remaining in `*智能镜头分类` are zero.

Current execution record:

- Inventory: `D:\Download\素材下载\团建视频\._采集记录\horizontal_scene_clips_inventory_20260703.csv`
- Deleted to Recycle Bin: `D:\Download\素材下载\团建视频\._采集记录\horizontal_scene_clips_deleted_to_recycle_20260703.csv`
- Post-delete check: `D:\Download\素材下载\团建视频\._采集记录\horizontal_scene_clips_post_delete_check_20260703.json`
- Result: 686 horizontal scene clips removed; 0 horizontal scene clips remain in smart scene libraries.
## Recent Corrections Learned From User Review

- Do not trust filename, spoken text, or hashtag keywords as the primary visual classifier. Use extracted frames / visual evidence first.
- If visual classification is unavailable, route low-confidence clips to review instead of forcing a project keyword.
- A clip that has no reliable bottom subtitle must not be cropped just because weak edges look text-like.
- Bottom-subtitle crop must keep the maximum usable picture. Use a dynamic margin near the subtitle top, not a fixed large margin.
- Batch crop is only safe for clips from the same source video or the same stable subtitle template. For a mixed scene library, inspect/detect each clip first; clean clips must be skipped.
- Plausible bottom subtitles should be a thin bottom band. Large bottom texture blocks, grass slopes, water ripples, roads, or scenery edges must be treated as visual texture, not subtitles.
- Do not delete original scene clips just because cropped derivatives exist. Original clips can be removed only after a reviewed replacement set is confirmed clean.
- Treat generated crop outputs as replaceable derivatives. Original scene clips remain the source of truth.
- When crop rules change, re-run generated crop derivatives with the new rule version instead of trusting old processed records.
- Small faint sky watermarks such as a channel name can be tested with deep repair, but if STTN/VSR still leaves readable text or obvious smear, do not batch it. For pure sky watermarks, a feathered sky-patch repair may be acceptable after visual comparison.
