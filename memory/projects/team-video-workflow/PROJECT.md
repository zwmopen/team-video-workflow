# 团建视频剪辑工作流项目记忆

## 项目定位

这是一个本地视频素材管理、素材清洗、分镜分类、音频文案提取、文案配镜、粗剪预览的长期工作流项目。

核心目标不是做一个花哨剪辑软件，而是让用户以后只需要采集/补充素材，AI 和本地工具就能完成：

1. 素材入库
2. 分镜切割
3. 分类和关键词整理
4. 字幕/水印/废料清洗
5. 音频和文案资产提取
6. 按文案匹配画面
7. 生成可进入剪映的粗剪素材和项目结果

## 当前核心位置

- Skill: `C:\Users\z\.codex\skills\teambuilding-video-scene-library`
- 分镜/素材工具: `D:\AICode\AI\tools\teambuilding-video-scene-library`
- 本地浏览器/工作流界面: `D:\AICode\AI\tools\team-video-library-browser`
- 素材库根目录: `D:\Download\素材下载\团建视频`
- 共享记录目录: `D:\Download\素材下载\团建视频\._采集记录`

## 重要工作原则

1. 所有长期项目都必须维护可交接的共享参考文档，方便 Codex、Claude、其他本地 AI 工具继续开发。
2. 每次改动工作流、判断规则、素材处理策略，都要写入项目文档或 Skill 参考文件，而不是只留在聊天里。
3. 大批量处理前必须先小样本测试，并生成清单、报告、对比图。
4. 素材原片默认不覆盖；废料或错误产物优先移动到回收站或记录清楚后删除。
5. 剪辑素材库以“可复用、可检索、可拖入剪映”为目标，不追求保留所有垃圾素材。

## 素材清洗双模式

1. `裁剪模式`
   - 用于底部字幕、黑边、单帧封面、标题废料、开头结尾废料。
   - 优点是快，适合批量。
   - 缺点是会改变画幅，横屏素材裁剪后经常变小，很多不再适合入库。

2. `深度修复模式`
   - 使用 VSR / AI inpainting 去硬字幕或水印。
   - 用于画面有价值、不能裁掉主体的片段。
   - 默认模式优先 `sttn-auto`，`opencv` 只做快速预览。
   - 必须先小样本测试，生成前后对比图，再批量。

## 2026-07-03 横屏素材规则

用户明确更新规则：

1. 已经分镜并裁剪过的横屏素材，如果裁剪后画面很小、基本不可用，应删除。
2. 横屏原片不再走裁剪模式作为主要清洗手段。
3. 横屏素材后续进入 `深度修复模式` 队列，尽量保留原画幅，修复字幕/水印后再判断能否作为备用素材。
4. 竖屏素材当前勉强可用，优先不动。

执行记录：

- 已识别裁剪分割产物 708 条。
- 已标记横屏裁剪分割产物 164 条。
- 164 条已移动到 Windows 回收站。
- 删除清单: `D:\Download\素材下载\团建视频\._采集记录\horizontal_crop_split_deleted_to_recycle_20260703.csv`
- 横屏/伪竖屏原片深度修复队列: `D:\Download\素材下载\团建视频\._采集记录\horizontal_source_deep_repair_queue_20260703.csv`
- 队列合计 32 条，约 1790 秒原片，CPU `sttn-auto` 粗估约 52 小时。

## 接手提示

如果其他 AI 接手，请先读：

1. `C:\Users\z\.codex\skills\teambuilding-video-scene-library\SKILL.md`
2. `C:\Users\z\.codex\skills\teambuilding-video-scene-library\references\deep-repair-mode.md`
3. `D:\AICode\AI\tools\team-video-library-browser\PROJECT_HANDOFF_PROMPT.md`
4. 本文件

之后再查看 `D:\Download\素材下载\团建视频\._采集记录` 中最新报告。
