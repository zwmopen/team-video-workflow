@echo off
title 在线相册服务 - 一键秒级重启
chcp 65001 >nul
cd /d "%~dp0"
python "%~dp0restart_online_gallery.py"
echo.
pause
