@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>&1
if errorlevel 1 (
    echo Python 3.9 or newer is required. Install it from python.org, or use BotControl.exe.
    pause
    exit /b 1
)
py -3 server.py
if errorlevel 1 (
    echo BotControl could not start. Check that Python 3.9+ is installed and port 8765 is free.
    pause
)
