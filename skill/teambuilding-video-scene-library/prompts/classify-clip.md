# Clip Classification Prompt

Use this prompt when keyframes are available and visual AI classification is needed.

Input:

- Location name.
- Source video name.
- Clip duration and resolution.
- Three keyframes from 20%, 50%, and 80%.
- Existing filename or sidecar text if available.

Return strict JSON:

```json
{
  "primary_category": "05_项目活动",
  "subcategory": "皮划艇",
  "scene": "湖面",
  "activity": "皮划艇竞速",
  "people_count": "多人",
  "action": "团队竞速",
  "emotion": "开心欢呼",
  "shot_scale": "全景",
  "camera_angle": "侧面跟拍",
  "time_of_day": "白天",
  "semantic_tags": ["湖面", "多人", "竞速"],
  "usage_tags": ["开场候选", "高能段落"],
  "quality_comment": "画面清晰，动作明显",
  "confidence": 0.91,
  "review_reason": ""
}
```

Rules:

- Choose exactly one `primary_category`.
- Use `90_待人工分类` when confidence is low or the visual content is unclear.
- Put secondary meaning in tags, not additional file copies.
