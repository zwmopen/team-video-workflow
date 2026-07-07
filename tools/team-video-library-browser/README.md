# 团建素材库浏览器

本地素材管理界面，不上传素材，不复制素材。

## 启动

剪辑素材浏览器，优先给剪映用：

```text
D:\AICode\AI\tools\team-video-library-browser\启动剪辑素材浏览器.bat
```

网页预览器，作为备用浏览：

双击：

```text
D:\AICode\AI\tools\team-video-library-browser\启动素材库浏览器.bat
```

或直接打开服务地址：

```text
http://127.0.0.1:8765
```

## 当前扫描范围

- `D:\Download\素材下载\团建视频\01_原片素材库\*-原视频素材`
- `D:\Download\素材下载\团建视频\02_分镜素材库\*智能镜头分类`
- `D:\Download\素材下载\团建视频\_自动粗剪*`
- `D:\Download\素材下载\团建视频\*成品区`
- `D:\Download\素材下载\团建视频\*智能匹配工作流`

不扫描临时缓存、采集记录目录和系统记录目录。成品工作流目录会进入“成品粗剪 / 成品检查”视图。

## 当前功能

- 按原片 / 分镜筛选
- 按地点筛选
- 按分类和关键词筛选
- 搜索文件名、地点、分类、关键词
- 缩略图预览
- 点击后右侧播放视频
- 网页端会记住“打开声音 / 静音播放”状态
- 剪辑素材浏览器支持多选后直接拖到剪映时间线
- 成品检查区会显示 `rough_cut.mp4`、无声画面轨、剪映素材包和工作流分段，方便回看与交付检查
- 文案配镜默认链路是：`01_原片素材库\<地点>-原视频素材` 提取到 `03_音频文案库`，再用 `02_分镜素材库\<地点>智能镜头分类` 匹配画面并生成审片板和粗剪
- 音频主线可以先用 `list-audio-library <地点智能镜头分类>` 查看，再用 `--audio-index` 或 `--audio-query` 指定某条原片口播
- 审片反馈表里的 `替换关键词` / `重配关键词` 会先触发候选画面重配，再进入粗剪；不是只在最后换序号
- 右侧预览区支持 `手动处理`：画面裁剪和时间裁剪都会输出到源文件旁边的 `手动处理` 文件夹，不覆盖原片
- 删除操作会移动到电脑回收站，不再放入项目里的废料目录
- `素材裁剪切割` 是一个合并界面：上方框选画面，下方拖动时间线，默认跳过首帧，按钮叫 `输出新素材`
- 筛选结果刷新后会默认选中第一条素材并自动播放，减少手动点击
- 交接提示词：`D:\AICode\AI\tools\team-video-library-browser\PROJECT_HANDOFF_PROMPT.md`
## Transcript Copy Cleanup

- The right preview action `复制视频文案 / 识别并复制文案` post-processes transcript text before copying.
- Post-processing includes Traditional-to-Simplified conversion through `opencc-python-reimplemented`, basic punctuation, line breaks, and cache rewrite.
- If the ASR model hears a word wrong, the cleanup layer will not invent the correct word. Use a stronger ASR model or source `.txt` sidecar for higher text accuracy.

## Safe Batch Subtitle Crop

- `批量裁剪字幕` must not overwrite source clips.
- Formal execution writes new videos into a sibling `字幕之上` folder next to each source clip.
- Generated outputs under `字幕之上` and `手动处理` are skipped on later batch runs to avoid repeated crop nesting.
- `cropped_records.json` records the original source item id after a successful safe output.
