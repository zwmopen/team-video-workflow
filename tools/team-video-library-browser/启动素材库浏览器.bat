@echo off
set TB_LIBRARY_PORT=8765
start "" http://127.0.0.1:8765
python "%~dp0server.py"
