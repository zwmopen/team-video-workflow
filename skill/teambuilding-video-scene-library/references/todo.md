# 团建视频工作流待办

This file is the live task list for the user's reusable team-building video workflow.

## Current Goal

Build a low-touch workflow where the user only adds new source videos or gives a script/reference video, and Codex can collect, split, classify, match, rough-cut, and prepare Jianying-ready materials.

## Checklist

- [ ] Add reference-video learning layer before large-scale rough cuts.
  - Analyze vertical finished/source videos into transcript beats, shot keyframes, visual keywords, and line-to-picture mappings.
  - Produce statistics for common phrase -> picture patterns, average shot length, clips per sentence, opening/climax/ending structures, and bad-repeat patterns.
  - Store local outputs under `D:\Download\素材下载\团建视频\00-模板库\参考成片学习库`.
  - Publish sample learning tables to Feishu for review.
- [x] Build the first reference-audio visual replacement MVP.
  - Output: `rough_cut.mp4`, `reference_audio.m4a`, `script.txt`, `recompose_plan.csv`, `summary.json`, `jianying_pack`.
  - Verified by `check-delivery`.
- [x] Add delivery self-check for rough-cut folders.
  - Checks vertical video, audio stream, plan CSV, script, and Jianying pack.
- [x] Integrate Jianghu Toolbox FFmpeg/FFprobe fallback.
  - Use local toolbox binaries when system `ffprobe` is missing.
- [x] Create the first template library.
  - Script templates, structure templates, keyword-to-shot rules, and audio/transcript matching notes.
- [x] Record open-source upgrade map.
  - Learned from Remotion, auto-editor, PySceneDetect, Whisper/WhisperX, OpenTimelineIO, and reference-video understanding patterns.
- [x] Fix rough-cut fallback matching so it does not randomly pull unrelated clips when a keyword has no exact match.
  - Related-category fallback is allowed; whole-library random fallback is not allowed for concrete targets.
- [x] Improve rough-cut shot matching quality.
  - Better sentence splitting.
  - Better source diversity.
  - Avoid repeated clips from the same source when possible.
  - Prefer concrete visual keyword over broad category.
  - 2026-07-01: tightened fallback so concrete script beats no longer pull unrelated whole-library clips.
  - 2026-07-01: clean replacement libraries can now be scanned directly even without `project.sqlite`.
  - 2026-07-01: added delivery warnings for repeated material, unmatched beats, long one-clip beats, and `待人工分类` usage.
  - 2026-07-01: tightened concrete activity matching again: a beat such as `皮划艇` must match the concrete subkeyword, and cannot be filled by another `05_项目活动` clip such as cycling, food, rafting, or generic project footage. If no direct concrete match exists, mark the beat unmatched/weak instead of pretending it is correct.
- [x] Add visual audit contact sheets for messy classified libraries.
  - Generate contact sheets by folder/source sequence.
  - Produce correction CSV for full-folder cleanup.
  - Apply correction and update SQLite/CSV/JSON records.
- [ ] Re-clean existing libraries: 千岛湖, 安吉, 莫干山.
  - Goal: folder names match visible picture, not narration pollution.
  - Lock corrected records so future reruns do not undo manual/AI corrections.
- [x] Add subtitle/watermark removal as a separate material-cleaning module.
  - First research and pilot on a small batch.
  - Prefer inpainting/crop/blur only when it preserves useful footage.
  - Never overwrite the reusable source clip; write clean versions to a separate clean-material output.
- [x] Learn from the user's plugin/skill market spreadsheet and clone reference projects.
  - Spreadsheet: `D:\Program Files\xwechat_files\wxid_h6ggrsp6mpg522_dec2\msg\file\2026-06\AI口播自动剪辑插件与Skills市场盘点_对抗审查服务交付版.xlsx`.
  - Local summary: `D:\reference_learning_xlsx_summary.json`.
  - Local reference repos: `D:\AICode\AI\tools\external-video-reference`.
  - Treat this spreadsheet as a continuing evolution material box, not a one-time reading task. Future spreadsheets, repositories, and tool lists should be added to `open-source-upgrade-map.md` and evaluated before building new features.
- [x] Create a self-checked clean replacement library.
  - Adaptive crop source: `D:\Download\素材下载\团建视频\团建素材库_自检清洗版_20260701`.
  - Final usable v2 output: `D:\Download\素材下载\团建视频\团建素材库_最终可用干净替代版_20260701_v2`.
  - Result: 957 clips, 428 kept, 529 replaced, 0 pending AI repair, 0 root-level loose mp4 files, 957 silent clips.
- [ ] Upgrade subtitle/watermark removal from crop baseline to AI inpainting.
  - Detect subtitle/watermark masks.
  - Use video inpainting when available.
  - Reject clips where cleanup damages faces, hands, food, or project action.
  - Next tool to install/test: `video-subtitle-remover` deep inpainting modes on real lower-subtitle clips.
  - 2026-07-01: created VSR environment at `D:\AICode\AI\tools\external-video-reference\video-subtitle-remover\.venv`.
  - 2026-07-01: installed PaddleOCR, PaddlePaddle CPU, Torch CPU, and fixed one local VSR f-string syntax issue.
  - 2026-07-01: added wrapper script `scripts/vsr_clean.ps1`.
  - Current limitation: VSR can run, but graphical stickers/poster text may not be detected as subtitles. These need visual filtering or separate mask/inpaint rules.
  - 2026-07-01: added `audit-overlays` to detect bottom subtitles, top/center watermark text, mixed overlays, and generate contact sheets.
  - 2026-07-01: full audit of `团建素材库_最终可用干净替代版_20260701_v2`: 957 clips, 132 clean candidates, 825 dirty overlay candidates, 0 failed.
  - 2026-07-01: created true clean candidate library at `D:\Download\素材下载\团建视频\团建素材库_无字幕水印干净候选_20260701`; second audit passed with 132 clean, 0 dirty, 0 failed.
  - 2026-07-01: recorded the rule that crop-only/adaptive replacement libraries must not be called final clean libraries.
- [x] Add transcript-assisted reference workflow.
  - Extract original audio.
  - Transcribe with timecodes.
  - Use transcript only for beat timing and script matching, not for final visual folder classification.
  - 2026-07-01: implemented `learn-reference-videos --transcribe-audio` in `D:\AICode\AI\tools\teambuilding-video-scene-library`.
  - 2026-07-01: ran the first MVP learning set at `D:\Download\素材下载\团建视频\00-模板库\参考成片学习库\20260701_安吉千岛湖莫干山_音频时间戳成片学习_MVP`.
  - Output includes `成片学习表.csv`, `reference_learning.json`, `成片学习报告.md`, Whisper transcript JSON, keyframes, and contact sheets.
- [x] Add Feishu review board as the default human review surface.
  - User-owned board: `https://my.feishu.cn/docx/YanldTyg5oPwgnxahn6cCIGdnbd`.
  - User-provided copied storyboard template: `https://my.feishu.cn/wiki/JbQ3wak0viZRbzkhc1fcBY7onTf`.
  - Columns: script line, visual keyword, screenshot/visual, selection reason, and status. Feishu tables should not show local paths by default.
  - Rule: local CSV/JSON stay as machine traces, but Feishu is the user's primary review and correction interface.
  - 2026-07-01: one-line test succeeded. Wrote `测试 001｜安吉漂流配镜闭环` to Feishu, inserted a local keyframe image, and verified the image token exists in the document.
- [x] Automate Feishu review-row publishing.
  - Convert the manual test steps into one command: choose candidates, extract keyframes, append/update Feishu rows, upload images, and write local trace JSON.
  - Prefer image-in-row. If the API does not support it, fix the publishing method before using a path-only table.
  - 2026-07-01: `learn-reference-videos --publish-feishu true` appended the MVP learning table to `https://my.feishu.cn/docx/YanldTyg5oPwgnxahn6cCIGdnbd` and uploaded 4 contact-sheet images.
  - 2026-07-01: upgraded Feishu output to a pure image table: screenshots are embedded directly in table cells, temporary image blocks are deleted, and local paths stay only in CSV/JSON traces.
- [ ] Add final rough-cut style check against vertical Douyin reference videos.
  - Duration, rhythm, vertical framing, clip density, and whether the visual directly matches the spoken keyword.
- [x] Add material demand radar before collection and editing.
  - 2026-07-01: added `analyze-material-demand`.
  - 2026-07-01: ran `20260701_三地原视频文案素材需求雷达` for 安吉、千岛湖、莫干山.
  - Output: 50 raw video copy rows, 77 keyword-detail rows, 37 suggested collection searches, and 12 priority collection items.
  - Feishu rule: show keyword/search gaps only; keep local paths and complete transcripts in CSV/JSON.
  - 2026-07-01: rendered five checked MVP rough cuts at `D:\Download\素材下载\团建视频\_auto_roughcut_5_20260701_v8_cleanlib_checked`.
  - Machine check: 5/5 vertical, audio present, no repeated exact material, no unmatched beats, no `待人工分类` warnings.
  - Visual check: still sees center sticker/poster text in some source clips, so material cleaning remains the next bottleneck.
- [ ] Evaluate Remotion only after the material library is clean.
  - Use Remotion for template rendering/polish, not as the first split/classify engine.
- [x] Add feedback keyword rerouting for the smart match board.
  - 2026-07-02: `build-match-board` and `smart-match-workflow` now accept `--feedback-file`.
  - `替换关键词` / `重配关键词` is applied before candidate search, so the review board regenerates visual candidates instead of only changing the final rough-cut order.
  - Verified with automated tests and a real 千岛湖 feedback smoke test.

## Operating Rule

For editing output, a missing or weak match is better than an obviously wrong match. The system should mark a beat as unmatched or low-confidence instead of filling it with unrelated visuals.

## 2026-07-07 Source Backfill Rule

- New collected videos should be moved into `D:\Download\素材下载\团建视频\01_原片素材库\<地点>-原视频素材`; do not keep duplicated copies in the download/collection folder after successful import.
- Backfill processing should run by location, not by the whole disk: import source videos, run `crop_waste_and_split_originals.py`, refresh the browser index, then generate a material-demand report.
- The clean scene library should only accept vertical or useful pseudo-vertical outputs. Horizontal originals may remain as source/audio evidence, but horizontal scene clips should not be forced into the reusable scene library.
- Batch crop/split is allowed for rule-based preprocessing, but the final “clean usable library” still needs visual review. Clean clips are kept; dirty subtitles, watermarks, big title cards, and bad crops are recorded for later repair or discard.
- Current 2026-07-07 run report: `D:\Download\素材下载\团建视频\90_待整理与记录\原片补处理计划与结果_20260707.md`.
