@echo off
REM DSH-110：一键启动「在线相册」电脑端服务
REM 双击此 cmd → 后台跑 start_online_gallery_service.ps1（pythonw.exe 无黑框）
REM 开机自启已通过 install-autostart.ps1 注册到「启动」文件夹

setlocal
cd /d "%~dp0"
powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0start_online_gallery_service.ps1"
endlocal
exit /b %ERRORLEVEL%