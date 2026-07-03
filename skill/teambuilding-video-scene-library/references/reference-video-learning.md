# 参考成片学习层

Use this reference when the user asks whether Codex should analyze original/reference videos before editing, including "每一句台词对应什么画面", "统计规律", "学习升级", or "先学素材再剪".

## Position

This is the missing layer between `素材建库` and `文案智能配镜`.

It learns from finished/source videos:

```text
成片视频
-> 音频/文案转文字
-> 按台词切成语义句
-> 按画面切成镜头
-> 对齐台词时间和镜头时间
-> 给每句台词标注画面关键词
-> 统计规律
-> 升级配镜规则
```

It is not model training in the heavy ML sense. It is a structured local knowledge base plus rules/evidence that Codex can reuse.

## What To Analyze

For each reference video:

- video path
- same-stem transcript `.txt`, if present
- audio transcript with timecodes, if needed
- shot boundaries
- keyframes at each shot
- visible picture tags
- spoken-line to shot mapping
- rhythm: seconds per beat, clips per sentence, repeat usage
- role: opening, setup, project reveal, atmosphere, transition, climax, ending, CTA

## Output Schema

Each row should contain:

- `reference_video`
- `location_or_theme`
- `line_index`
- `spoken_line`
- `line_start`
- `line_end`
- `shot_start`
- `shot_end`
- `duration`
- `visual_keyword`
- `primary_category`
- `shot_role`
- `match_type`: `direct`, `atmosphere`, `transition`, `fallback`
- `keyframe_path`
- `source_video_path`
- `why_this_picture`
- `quality_note`

## Statistics To Produce

- common script phrase -> visual keyword pairs
- project words -> best picture categories, for example `漂流 -> 漂流艇/水花/头盔救生衣`
- location words -> scenery/arrival/common establishing shots
- food words -> table, dishes, close-up, group dining
- night words -> barbecue, campfire, fireworks, KTV
- average beat duration
- average shots per sentence
- percentage of direct visual matches vs atmosphere-only B-roll
- opening patterns
- climax patterns
- ending/CTA patterns
- low-quality patterns to avoid, such as one short clip repeated too many times

## Feishu Review Shape

Use the Feishu review board when checking a learned sample:

| 台词 | 实际原片画面 | AI理解关键词 | 可复用规则 | 备注 |
|---|---|---|---|---|

The right side should include a keyframe thumbnail and local/video source path when possible.

## Practical Rollout

1. First learn 3-5 high-quality vertical reference videos.
2. Produce a small rule summary and show it in Feishu.
3. Use the learned rules to create one rough cut.
4. Compare rough cut against the learned references.
5. Only then scale to all vertical source videos.

Avoid analyzing every poor or mixed-location source equally. Bad samples teach bad habits.
