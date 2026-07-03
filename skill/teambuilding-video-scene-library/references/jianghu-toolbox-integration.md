# Jianghu Toolbox Integration Notes

Installed shortcut target:

```text
D:\Program Files\江湖工具箱\江湖工具箱.exe
```

Useful discovered components:

```text
D:\Program Files\江湖工具箱\JHlib\ffmpeg\ffmpeg.exe
D:\Program Files\江湖工具箱\JHlib\ffmpeg\ffprobe.exe
D:\Program Files\江湖工具箱\MinApp\yt-dlp.exe
D:\Program Files\江湖工具箱\MinApp\DY提取作品.exe
D:\Program Files\江湖工具箱\MinApp\XHS提取作品.exe
D:\Program Files\江湖工具箱\MinApp\批量视频分割.exe
D:\Program Files\江湖工具箱\MinApp\批量视音频语音转写文案.exe
D:\Program Files\江湖工具箱\MinApp\火山视音频语音转写文案.exe
```

Integration policy:

1. Prefer non-GUI callable binaries and downloaded output folders before operating the visual frontend.
2. Do not modify the installed toolbox executable directly.
3. Reuse its bundled `ffprobe` when the system PATH does not expose ffprobe.
4. Treat `DY提取作品.exe` and similar MinApp programs as GUI/unknown-interface until a dedicated CLI test confirms supported arguments.
5. For the current team-building video workflow, the stable path is: collect/download output -> `00-待分类整理库` -> local skill pipeline.
