@echo off
REM DSH-110：一键查看「在线相册」服务状态
REM 输出：IP + 端口 + 总作品数 + 文件监听状态（🟢 实时监听 / 🟡 仅手动刷新 / ❌ 未运行）

setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check_online_gallery.ps1"
echo.
pause