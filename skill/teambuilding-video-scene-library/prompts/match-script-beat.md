# Script Beat Matching Prompt

Use this prompt when matching a script beat to candidate clips from a location library.

Input:

- One script beat.
- Location.
- Candidate clips with category, subcategory, tags, duration, source video, quality, and output path.
- Previously selected source videos.

Return strict JSON:

```json
{
  "beat_index": 3,
  "script_text": "下午体验皮划艇竞速，大家一路加油一路笑",
  "selected_clips": [
    {
      "clip_path": "D:/.../A_千岛湖_项目活动_皮划艇_0008__V012_S003.mp4",
      "reason": "皮划艇竞速和多人动作强对应",
      "suggested_duration": 1.6
    },
    {
      "clip_path": "D:/.../A_千岛湖_人物反应_笑脸_0019__V003_S011.mp4",
      "reason": "补足加油和开心反应",
      "suggested_duration": 1.2
    }
  ],
  "fallback_needed": false
}
```

Rules:

- Prefer strong visual correspondence over generic scenery.
- Avoid consecutive clips from the same source video when comparable alternatives exist.
- Build rhythm: environment wide, project middle, action detail, people reaction, team wide.
- Use one clip for short phrases and two or three clips for long dense sentences.
