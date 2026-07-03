# Content Production Line

This workflow is not ordinary video editing. It is a semi-automatic team-building short-video production line:

1. Build a reusable material library by location.
2. Split downloaded competitor/travel Vlogs into silent independent shots.
3. Classify shots by scene and project.
4. Keep rich tags in records: location, project, action, mood, shot scale, time, quality, source, timecode.
5. When the user provides a script, split it into semantic beats.
6. Match each beat to one or more clips from the location library.
7. Copy selected clips into a numbered edit pack.
8. The user imports the pack into Jianying and does final timing, audio, captions, and taste decisions.
9. When the user wants lower participation, start from `D:\Download\素材下载\团建视频\00-待分类整理库` and use `00-模板库` as the reusable template layer.

Keep this separation:

- Location library builder = ingest, split, mute, classify, record.
- Script-to-shot matcher = choose, order, number, copy.
- Template library = reusable copywriting templates, video rhythm templates, keyword-to-shot mapping.
- Audio transcript = timecode evidence for matching and review, not the only source of truth for physical folder classification.
- Reference-audio recomposer = keep a sample video's audio/script rhythm, replace visuals with vertical library clips, render `rough_cut.mp4`, and create a `jianying_pack`.
- Copywriting and cover generation are separate later skills or modes.

The user wants speed and reuse, not a fully automatic editor.

Low-touch target:

1. User drops new videos and same-stem text files into the inbox.
2. Codex extracts the requested location/theme, builds or updates the scene library, and runs self-checks.
3. User provides a script or chooses a template.
4. Codex rewrites/fills the script, matches each beat to concrete clips, and creates a numbered edit pack.
5. When a reference video is provided, Codex may instead keep that audio as the timeline and render a 9:16 rough cut with replaced visuals.
6. The final manual work should be only taste-level trimming, music, captions, and export.
