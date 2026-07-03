# 团建视频剪辑工作流工具交接提示词

你正在继续开发一个本地素材管理与剪辑辅助工具，不是普通网站。

## 项目位置

- 本地浏览器工具：`D:\AICode\AI\tools\team-video-library-browser`
- 主服务文件：`D:\AICode\AI\tools\team-video-library-browser\server.py`
- 启动地址：`http://127.0.0.1:8765/`
- 素材库根目录：`D:\Download\素材下载\团建视频`
- 相关 Codex Skill：`C:\Users\z\.codex\skills\teambuilding-video-scene-library\SKILL.md`

## 用户真实目标

用户是做短视频运营的，核心目标是：

1. 把下载来的团建/旅行/口播素材整理成可复用素材库。
2. 在本地工具里快速筛选、预览、拖拽到剪映。
3. 对素材做轻量手动处理：画面裁剪、时间切割、删除、重命名、加标签。
4. 后续用文案/音频自动匹配素材，生成粗剪。

当前优先做的是本地素材浏览器和“素材裁剪切割”交互。

## 当前工具已实现

- 扫描 `D:\Download\素材下载\团建视频` 下的原片、分镜素材、未整理素材、成品粗剪。
- 左侧筛选：素材类型、地点、一级分类、具体场景/关键词。
- 中间网格显示素材缩略图。
- 右侧预览视频，点击素材后自动播放。
- 筛选后如果当前选中素材不在结果里，会自动选中第一条结果并播放。
- 右键菜单支持：重命名、添加标签、素材裁剪切割、打开文件夹、复制路径、移到回收站。
- 删除会进 Windows 回收站，不再放项目废料目录。
- 手动处理输出统一放到源文件旁边的 `手动处理` 文件夹。

## 当前正在做的功能：素材裁剪切割

目标：一个弹窗同时完成画面裁剪和时间切割。

交互要求：

1. 右侧按钮叫 `素材裁剪切割`。
2. 打开后弹窗标题也叫 `素材裁剪切割`。
3. 上方显示当前视频画面，可拖动蓝色框选择保留画面范围。
4. 下方显示时间线，可拖动开始/结束手柄选择保留时间范围。
5. 默认自动检测字幕线，尽量把底部字幕以下裁掉。
6. 默认跳过首帧：开始时间默认 0.05 秒，避免首帧封面/卡片污染素材。
7. 有 `当前点设为开始`、`当前点设为结束`、`重置全片`。
8. 已保存布局可选择、保存，也必须可删除。
9. 最终按钮叫 `输出新素材`，不是“生成裁剪版”。
10. 输出新素材不覆盖原片，写入 `手动处理` 文件夹。

后端接口：

- `POST /api/manual-process`
  - 参数：`id`, `x`, `y`, `w`, `h`, `start`, `end`
  - 同时应用画面 crop 和时间 trim
  - 使用 FFmpeg 输出新素材
- `POST /api/delete-crop-layout`
  - 参数：`name`
  - 删除保存的裁剪布局

画质要求：

- 裁剪/切割会重新编码，不是数学意义的无损。
- 当前用 `libx264 -crf 18 -preset veryfast -pix_fmt yuv420p`，属于较高清保守输出，文件会比低码率略大。
- 预览缓存可以低质量，但正式输出素材不要用低质量参数。

## 开发风格

- 用户喜欢本地工具、少解释、多直接改。
- UI 风格偏柔和拟物、窗口布局启动器风格。
- 不要做营销页，不要做复杂后台，功能要直接能用。
- 所有危险操作都要可恢复，删除进回收站。
- 原始素材不要覆盖，处理后输出新素材。

## 测试方式

每次修改后运行：

```powershell
python -m py_compile "D:\AICode\AI\tools\team-video-library-browser\server.py"
```

重启服务：

```powershell
$connections = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue
$owners = $connections | Select-Object -ExpandProperty OwningProcess -Unique
foreach($owner in $owners){ try { Stop-Process -Id $owner -Force } catch {} }
Start-Process -FilePath python -ArgumentList @('server.py') -WorkingDirectory 'D:\AICode\AI\tools\team-video-library-browser' -WindowStyle Hidden
```

检查页面：

```powershell
python - <<'PY'
import urllib.request
html=urllib.request.urlopen('http://127.0.0.1:8765/?v=check', timeout=5).read().decode('utf-8')
for word in ['素材裁剪切割','输出新素材','删除布局','/api/manual-process']:
    print(word, word in html)
PY
```

## 继续开发时的第一句话

请先读取 `D:\AICode\AI\tools\team-video-library-browser\PROJECT_HANDOFF_PROMPT.md` 和 `server.py`，不要重写项目。继续完善“素材裁剪切割”弹窗：画面裁剪和时间线在同一个界面，默认跳过首帧，自动检测字幕线，保存布局可删除，输出按钮叫“输出新素材”，筛选后默认选中第一条并自动播放。
## Critical Safety Rule: Batch Crop

- `批量裁剪字幕` must never delete, overwrite, or replace source clips.
- Formal execution writes new clips into a sibling `字幕之上` folder next to the original clip.
- Outputs already inside `字幕之上` or `手动处理` must be skipped on later batch runs.
- Do not reintroduce PowerShell `Remove-Item` / `Move-Item` replacement logic for batch crop.
