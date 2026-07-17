@echo off
setlocal
cd /d "%~dp0desktop"
where node >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js 20+ is required.
  echo Download it from https://nodejs.org/
  pause
  exit /b 1
)
start "" "http://127.0.0.1:45832"
node server.mjs
pause
