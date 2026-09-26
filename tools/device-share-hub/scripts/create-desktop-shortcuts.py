#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH-110: 在桌面创建 3 个 .lnk 快捷方式（无 emoji，COM 兼容）。"""
import os
import sys

# ⚠️ 本脚本会打印中文。Windows 上 Python 默认按 locale 编码输出（cp1252），
#    一打印中文就 UnicodeEncodeError 崩掉，而且崩在「打印」而不是「判定」上，
#    很容易被误读成闸门失败。固定 utf-8，保证在任何 runner / 任何 locale 下都能跑。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


try:
    import win32com.client as wc
except ImportError:
    print("需要 pywin32（系统 Python 3.11 已自带）", file=sys.stderr)
    sys.exit(1)

desktop = os.path.expandvars(r"%USERPROFILE%\Desktop")
scripts_dir = r"D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\scripts"

print(f"Desktop: {desktop}")
shell = wc.gencache.EnsureDispatch("WScript.Shell")


def make_shortcut(name, target, icon_idx, desc):
    s = shell.CreateShortcut(os.path.join(desktop, name))
    s.TargetPath = target
    s.WorkingDirectory = scripts_dir
    s.IconLocation = r"%WINDIR%\system32\shell32.dll," + str(icon_idx)
    s.Description = desc
    if "启动" in name:  # 一键启动要最小化窗口
        s.WindowStyle = 7
    s.Save()


make_shortcut("在线相册.lnk", os.path.join(scripts_dir, "online_gallery.cmd"), 13,
              "DSH-110 一键启动电脑端在线相册服务")
make_shortcut("在线相册-状态.lnk", os.path.join(scripts_dir, "online_gallery-status.cmd"), 21,
              "DSH-110 查看在线相册服务状态（IP / 总作品数 / 文件监听）")
make_shortcut("在线相册-开机自启.lnk", os.path.join(scripts_dir, "online_gallery-autostart.cmd"), 166,
              "DSH-110 注册/卸载在线相册服务开机自启")

print("[OK] 3 lnks created")
for f in sorted(os.listdir(desktop)):
    if "在线相册" in f:
        print(f"  {f}")