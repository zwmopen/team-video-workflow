# 深度修复模式

本文件记录素材清洗的第二条路线：在 `裁剪模式` 之外，使用 VSR / AI inpainting 去除硬字幕或水印文字。

## 两种清洗模式

1. `裁剪模式`
   识别字幕线、黑边、封面帧和标题废料，通过画面裁剪或时间切割生成可用素材。适合批量、速度快，但会改变画幅或丢掉部分画面。

2. `深度修复模式`
   使用 VSR / AI inpainting 保留原始画幅，尝试把硬字幕、水印文字从画面中修掉。适合珍贵画面、人物/项目动作/风景不能裁掉的片段。

## 默认决策

1. 能裁掉且不影响素材价值时，先走 `裁剪模式`。
2. 裁掉会损失人物、项目动作、食物、住宿空间、风景主体时，进入 `深度修复模式`。
3. 大标题、大贴纸、图文海报、封面废料仍然走 `裁切废料`，不走深度修复。
4. 深度修复必须先小样本测试，再批量。
5. 每次测试必须输出前后对比图。
6. 修复后若水面、天空、脸、手、食物、项目动作区域有明显涂抹痕迹，不得进入干净库。
7. 通过二次视觉复核后，才能标记为 `deep_repair_clean`。

## 本地工具

- 工具：Video Subtitle Remover / VSR
- 本地仓库：`D:\AICode\AI\tools\external-video-reference\video-subtitle-remover`
- 调用脚本：`C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\vsr_clean.ps1`
- 推荐优先模式：`sttn-auto`
- 快速试验模式：`opencv`，仅用于预览，不作为最终干净库标准

## 默认命令

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\vsr_clean.ps1" `
  -InputVideo "<input.mp4>" `
  -OutputVideo "<output.mp4>" `
  -Mode sttn-auto `
  -YMin 900 -YMax 1920 -XMin 0 -XMax 1080
```

## 2026-07-03 样片记录

- 输入：`D:\Download\素材下载\团建视频\安吉智能镜头分类\05_项目活动\漂流\002_安吉_漂流_裁剪分割__CV008_S001.mp4`
- 输出目录：`D:\Download\素材下载\团建视频\._采集记录\VSR_AI去字测试_20260703`
- 片段：720x860，约 0.90 秒，27 帧
- `opencv`：约 158 秒，能去字，但底部有明显糊块
- `sttn-auto`：约 97 秒，效果明显更自然，但仍有轻微修复痕迹

## 批量准入

1. 先从同一来源/同一字幕样式中抽 5-10 条短片测试。
2. 生成 `before / opencv / sttn-auto` 三联图。
3. 人工或视觉 AI 复核通过后，再扩大到批量。
4. 批量结果仍需抽检；不能因为命令成功就直接入库。
5. 对静音分镜素材，VSR 可能出现音频提取失败提示，只要视频输出正常可播放即可；对原片素材，必须额外保留或重新合并音频。

## 2026-07-03 横屏素材规则

用户确认：

1. 横屏素材通过裁剪模式处理后，画面通常会变得很小，复用价值低。
2. 已经分镜裁剪过的横屏产物应从可用分镜库删除。
3. 横屏原片后续不再优先走裁剪模式，而是进入 `深度修复模式` 队列。
4. 竖屏素材当前可先保留，不做全量深度修复。

当前执行记录：

- 已识别并删除到回收站的横屏裁剪分割产物：164 条。
- 删除清单：`D:\Download\素材下载\团建视频\._采集记录\horizontal_crop_split_deleted_to_recycle_20260703.csv`
- 横屏/伪竖屏原片深度修复队列：`D:\Download\素材下载\团建视频\._采集记录\horizontal_source_deep_repair_queue_20260703.csv`
- 队列规模：32 条，约 1790 秒原片，CPU `sttn-auto` 粗估约 52 小时。

后续实现要求：

1. 深度修复批处理必须支持断点续跑。
2. 每条输出必须记录来源、模式、耗时、输出路径和复核状态。
3. 每批必须生成前后对比图。
4. 批量任务默认先跑短片或低峰运行，不阻塞日常剪辑。

当前可用批处理入口：

```powershell
& "C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\deep_repair_horizontal_queue.ps1" -MaxItems 3 -DryRun
```

正式执行时去掉 `-DryRun`。脚本会读取：

```text
D:\Download\素材下载\团建视频\._采集记录\horizontal_source_deep_repair_queue_20260703.csv
```

默认输出到：

```text
D:\Download\素材下载\团建视频\深度修复横屏原片
```
