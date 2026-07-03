# Module Status

Use this as the current implementation checklist.

## Done

- Source collection and cleanup.
- Vertical scene library building.
- Keyword refinement.
- Clip renaming.
- Script-to-shot edit pack.
- Reference-audio visual replacement rough cut.
- Delivery check for rough-cut folders.
- Jianghu Toolbox ffprobe integration.
- Open-source upgrade map.
- Modular skill architecture.
- Live task list at `references/todo.md`.
- Safer reference-recompose fallback: concrete script beats no longer fall back to unrelated whole-library clips.
- Stricter concrete activity matching: same top-level category is not enough for a concrete beat; the clip must match the requested subkeyword, otherwise the beat stays unmatched/weak.
- Visual audit contact sheets and correction CSV template.

## Current Delivery Check

Command:

```powershell
python D:\AICode\AI\tools\teambuilding-video-scene-library\main.py check-delivery <output-folder>
```

Checks:

- `rough_cut.mp4` or another mp4 exists.
- Video is vertical when expected.
- Video has an audio stream.
- `jianying_pack` exists and contains clips.
- A plan CSV exists and has rows.
- A script text file exists when available.

Outputs:

- `delivery_check.json`
- `delivery_check.md`

## Next Best Module

Implement visual audit contact sheets and correction CSV generation before adding Remotion polish. Clean material quality matters more than fancy rendering.

After the first MVP rough cut, the highest-value fixes are:

1. Fill or auto-suggest correction rows from visual audit sheets, then re-clean 千岛湖, 安吉, and 莫干山 classified libraries.
2. Improve beat-to-shot matching so script lines map to concrete visible keywords.
3. Pilot subtitle/watermark removal as a separate clean-material module.
