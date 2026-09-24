@echo off
REM DSH-110：一键注册/卸载「在线相册」开机自启
REM 第一次运行：注册；之后任何时候运行：提示已注册或注册失败
REM 卸载：powershell -File "%~dp0install-autostart.ps1" -Uninstall

setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-autostart.ps1"
echo.
pause