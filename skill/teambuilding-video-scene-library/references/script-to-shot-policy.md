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

## 初检 / 初剪素材包工作流

当用户在本地工具里选择一条 `原片音频素材` 并点击 `复制提示词并开始匹配素材` 后，Codex 应执行这个工作流，而不是只依赖浏览器里的本地关键词匹配。

输入：

- 一条 `.m4a/.mp3/.wav` 原片音频素材。
- 同名时间戳文案 TXT 或转写缓存。
- `D:\Download\素材下载\团建视频` 下已清洗、已按地点和活动分类的分镜素材库。

输出：

- `D:\Download\素材下载\团建视频\智能剪辑初剪库\<日期时间_音频标题>\`
- `001_...mp4`, `002_...mp4`, `003_...mp4` 这样的顺序编号素材。
- `文案.txt`
- `配镜表.csv`
- `配镜说明.md`
- `质检报告.md`
- 可行时输出 `rough_cut_preview.mp4`，只作为快速预览，不替代剪映精剪。

匹配规则：

1. 先按时间戳文案切成台词 beat。正常是一句话一个画面。
2. 如果一句话超过约 3 秒，或者包含多个关键词，匹配 2-3 个画面。
3. 先做语义匹配，再做关键词匹配、地点匹配、分类文件夹匹配。
4. 具体项目必须具体匹配：皮划艇、骑行、游艇、漂流、烧烤、民宿、露营、真人 CS 等不能互相顶替。
5. 没有明确关键词时，用同地点环境空镜、人物反应、团队互动、细节特写补足，不要循环同一条 1-2 秒素材。
6. 避免连续使用同一个源视频里高度相似的片段，除非素材库里确实没有替代。
7. 只复制素材，不移动、不改名源素材。
8. 如果素材明显带字幕、水印、废料、横屏小画面或分类不可信，不进入初剪素材包。

质检规则：

1. 检查每个台词 beat 是否有画面覆盖。
2. 检查具体项目是否错配。
3. 检查是否有明显重复循环。
4. 检查是否有脏字幕、水印、封面、贴纸、黑边。
5. 检查总画面时长是否覆盖音频时长。
6. 在 `质检报告.md` 里列出弱匹配台词、缺素材关键词和建议补采关键词。
