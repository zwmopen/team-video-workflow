# Open Source Upgrade Map

Use this file before adding major video-editing features. The goal is to absorb proven tools and patterns, not to hand-roll every function.

## Current Local Baseline

- FFmpeg: stable rendering, muxing, audio extraction, scaling, cropping.
- PySceneDetect: local shot boundary detection.
- MoviePy: installed locally; use for Python-native composition when FFmpeg command strings become too fragile.
- Jianghu Toolbox: provides bundled `ffmpeg`, `ffprobe`, `yt-dlp`, Douyin/XHS extractors, video splitters, and transcription utilities. Prefer callable binaries and output folders before GUI automation.

## External Projects To Learn From

## User Evolution Material Box

The user's spreadsheet is not a one-time reference. Treat it as a long-term material box for future workflow upgrades.

Primary file:

```text
D:\Program Files\xwechat_files\wxid_h6ggrsp6mpg522_dec2\msg\file\2026-06\AI口播自动剪辑插件与Skills市场盘点_对抗审查服务交付版.xlsx
```

Current local extraction:

```text
D:\reference_learning_xlsx_summary.json
```

Usage rule:

1. Before adding a major editing feature, check this material box and the local reference repos first.
2. Do not copy tools blindly. Extract useful patterns, then adapt them to the user's team-building workflow.
3. Current priority is still team-building: material collection, visual-first scene classification, subtitle/watermark cleaning, script-to-shot matching, reference-audio recomposition, Jianying-ready packs, and delivery self-check.
4. Future material boxes can be added to this section. The skill should keep learning from them, but only promote ideas that improve the local workflow.
5. If a tool is useful but not immediately runnable, record it as a candidate instead of blocking current production.

### video-use

Source: https://github.com/browser-use/video-use

Useful idea: treat video creation as an agent workflow where the user drops raw clips/images/audio, a coding agent writes edit code, and the expected final artifact is `final.mp4`.

Adopt:

- Keep a clear project folder per render.
- Always output a playable final video plus machine-readable plan files.
- Let code generate the edit instead of manually clicking a GUI.

Do not blindly adopt:

- Generic editing prompts. Our domain needs team-building keyword matching and reusable local material libraries.

### Remotion

Sources:

- https://github.com/remotion-dev/remotion
- https://www.remotion.dev/docs

Useful idea: video as code, especially reusable templates, captions, layouts, overlays, and deterministic rendering.

Adopt later:

- Use Remotion for polished recurring formats: title cards, subtitles, progress bars, route labels, branded lower-thirds, covers.
- Keep FFmpeg for fast rough-cut assembly; use Remotion when visual layout matters.

### auto-editor

Source: https://github.com/WyattBlue/auto-editor

Useful idea: automatic editing by analyzing audio/video signals, especially silence or motion.

Adopt later:

- Optional speed-up for removing silence, tightening source audio, and detecting dead air in reference videos.
- Treat as a helper for audio cleanup, not as the full team-building editor.

### PySceneDetect

Source: https://github.com/Breakthrough/PySceneDetect

Useful idea: robust scene detection as a library and CLI.

Already adopted:

- Keep using for shot splitting.

Improve:

- Store detector thresholds per source style.
- Add a second-pass split for clips still containing multiple obvious shots.

### WhisperX

Source: https://github.com/m-bain/whisperX

Useful idea: faster transcription plus word-level timestamps and alignment.

Adopt later:

- Replace rough Whisper segment matching with word-level timestamps when reference-audio recompose needs frame-accurate sentence timing.

### OpenTimelineIO

Source: https://github.com/AcademySoftwareFoundation/OpenTimelineIO

Useful idea: timeline interchange between tools.

Adopt later:

- Export rough cuts as timeline data, not only as mp4 and numbered folders.
- Create an interchange layer for Premiere/Resolve/FCPXML-style workflows if needed.

### FireRed-OpenStoryline / VideoWeaver Pattern

Sources:

- https://github.com/FireRedTeam/FireRed-OpenStoryline
- https://arxiv.org/abs/2410.19740

Useful idea: separate foundation skills or style skills from the main orchestration agent.

Adopt:

- Keep the main skill as a conductor.
- Split stable pipeline stages into small reusable workflow modules.
- Use a style/template layer for recurring short-video formats.

### User Spreadsheet: AI Spoken-Video Editing Tools And Skills

Source file:

```text
D:\Program Files\xwechat_files\wxid_h6ggrsp6mpg522_dec2\msg\file\2026-06\AI口播自动剪辑插件与Skills市场盘点_对抗审查服务交付版.xlsx
```

Local extracted summary:

```text
D:\reference_learning_xlsx_summary.json
```

Adopt:

- Treat the workflow as small composable skills: source collection, scene library, cleaning, reference-audio recomposition, script-to-shot pack, and delivery self-check.
- Keep a tool watchlist instead of rebuilding every feature blindly.
- Use external projects as patterns, then adapt to the user's local team-building material library.

### OpenMontage

Source: https://github.com/calesthio/OpenMontage

Local clone:

```text
D:\AICode\AI\tools\external-video-reference\OpenMontage
```

Useful idea: agentic video pipeline with explicit guides, pipeline definitions, skills, tools, and checkpoints.

Adopt:

- Split the workflow into stage directors with stage reports.
- Add self-review checkpoints after material cleaning and rough-cut rendering.
- Keep machine-readable plans next to every generated video.

### video-subtitle-remover

Source: https://github.com/YaoFANGUK/video-subtitle-remover

Local clone:

```text
D:\AICode\AI\tools\external-video-reference\video-subtitle-remover
```

Useful idea: real hard-subtitle/watermark removal by detecting subtitle areas and inpainting them with STTN/LAMA/ProPainter/OpenCV modes.

Current result:

- Tried CLI `opencv` mode on one local sample.
- Blocked before running because the repo environment is not installed yet: missing `qfluentwidgets`.

Adopt next:

- Build an isolated VSR environment.
- Test `--subtitle-area-coords` on vertical Douyin clips.
- Prefer deep inpainting for clips where cropping removes too much useful picture.
- Keep crop/adaptive replacement as a safe fallback when VSR fails.

### haoone-app

Source: https://github.com/minghe36/haoone-app

Local clone:

```text
D:\AICode\AI\tools\external-video-reference\haoone-app
```

Useful idea: subtitle/ASR/skill/CLI workflow references for content automation.

Adopt:

- Improve transcript handling and source-audio timing.
- Use transcripts for matching script beats, not as final visible-picture truth.

### claude-video-vision

Source: https://github.com/jordanrendric/claude-video-vision

Local clone:

```text
D:\AICode\AI\tools\external-video-reference\claude-video-vision
```

Useful idea: video understanding layer based on extracted frames plus timestamped audio.

Adopt:

- Treat each clip as keyframes plus transcript evidence.
- Use multimodal visual review for folder cleanup before trusting keyword routing.

## Decision Rules

1. Use FFmpeg first for deterministic low-level video work.
2. Use PySceneDetect for shot boundaries unless a better detector is tested on our real素材.
3. Use Whisper/WhisperX for audio-to-text timing, not as physical-folder truth.
4. Use Remotion only when template visuals/subtitles/brand style matter.
5. Use MoviePy when Python timeline logic is easier than shelling out many FFmpeg commands.
6. Use OpenTimelineIO only after rough-cut mp4 and Jianying pack are stable.
7. Do not install or vendor random public skills without source review and a rollback path.
