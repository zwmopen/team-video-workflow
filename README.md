# 团建视频剪辑工作流

这是本地「团建视频剪辑工作流」项目的私有云端备份仓库。

它保存的是可继续开发的工作流资产，不保存视频素材本体：

- Codex Skill：视频素材分镜、分类、裁去字幕、深度修复等规则。
- 本地工具：素材浏览器、批量处理、音频/文案提取、初剪工作流入口。
- 项目共享记忆：方便其他 AI 工具接手时理解当前约定。
- 处理报告：记录横屏素材清理、VSR/AI 去字测试和后续批量深度修复计划。

## 本地路径

- Skill：`C:\Users\z\.codex\skills\teambuilding-video-scene-library`
- 浏览器工具：`D:\AICode\AI\tools\team-video-library-browser`
- 分镜工具：`D:\AICode\AI\tools\teambuilding-video-scene-library`
- 项目记忆：`D:\AICode\AI\memory\projects\team-video-workflow`
- 素材库根目录：`D:\Download\素材下载\团建视频`

## 启动素材浏览器

```powershell
cd D:\AICode\AI\tools\team-video-library-browser
python server.py
```

打开：

```text
http://127.0.0.1:8765
```

## 当前核心规则

1. 原片不直接覆盖，清洗后输出新素材。
2. 分镜素材可以清洗、裁去字幕、删除废料；原片保留为可重切来源。
3. 清洗素材有两种模式：
   - 裁剪模式：裁去字幕、裁切废料、去黑边。
   - 深度修复模式：对无法裁剪但仍有价值的素材，用 VSR/AI 去字作为备用。
4. 横屏裁剪后的分镜普遍可用性差，旧的横屏裁剪分镜已经移入回收站；横屏素材后续优先走深度修复。
5. 以后每个项目都要保留项目共享文档，让其他 AI 工具可以继续接手。

## 不进入仓库的内容

- 视频、音频、图片素材
- 缩略图、预览缓存、转录缓存
- VSR 模型、虚拟环境、大型开源仓库
- 任何密钥或本地账号凭据

## 目录说明

```text
skill/      Codex Skill 与参考规则
tools/      本地执行工具源码
memory/     项目共享记忆和交接规则
reports/    最近一次关键处理报告
```
