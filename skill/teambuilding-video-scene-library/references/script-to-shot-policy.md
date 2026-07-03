# Script-To-Shot Policy

Split scripts by meaning, not mechanically by every line.

Match principles:

- Strong visual correspondence beats generic matching.
- `推窗就是湖景` should prefer room/window/balcony lake-view shots, not a random lake aerial.
- `一路尖叫一路笑` should prefer people laughing, splashing, cheering, or reaction shots.
- Long sentences may need 2-3 clips: project establishing shot, action middle shot, reaction close shot.
- Avoid using several consecutive clips from the same source video when alternatives exist.
- Avoid visual repetition: not three similar kayaking clips, not three wide shots, not three backs of people.
- Prefer an edit rhythm like: environment wide -> project middle -> action detail -> people reaction -> team wide.
- For concrete activity beats such as `皮划艇`, `骑行`, `漂流`, `真人CS`, `越野车`, or `烧烤`, a same-category clip is not enough. The selected clip must match the concrete subkeyword in its folder/tag/path evidence.
- If a concrete activity has no direct visual match, mark the beat weak/unmatched. Do not fill `皮划艇` with `骑行`, `吃饭`, `大巴`, generic `项目活动`, or random scenery.
- After at least one direct concrete match is selected, support shots may come from environment, team interaction, people reaction, or detail folders to avoid looping one short clip for a long sentence.

Output rules:

- Copy clips into a flat numbered pack.
- Prefix filenames with `001_`, `002_`, `003_`.
- Generate `文案.txt`, `配镜表.csv`, and `配镜说明.md`.
- Do not move or rename source library clips.
- If the user has not provided a full script, choose a suitable template from `D:\Download\素材下载\团建视频\00-模板库\文案模板`.
- Use `D:\Download\素材下载\团建视频\00-模板库\配镜规则\素材关键词映射.md` as the first-pass keyword map, then prefer visually locked clips when available.
- Original audio transcription can help infer what the source clip is about, but visible picture wins when transcript and picture disagree.
