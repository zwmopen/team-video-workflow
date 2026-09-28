@echo off
REM 在线相册桌面启动器与控制台 (双击运行)
title 在线相册电脑端控制中枢
chcp 65001 >nul
cd /d "%~dp0"
"C:\Users\z\AppData\Local\Programs\Python\Python311\python.exe" "%~dp0online_gallery_launcher.py"
