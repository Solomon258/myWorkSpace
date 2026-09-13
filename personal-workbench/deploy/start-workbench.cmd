@echo off
chcp 936 >nul
setlocal
title 个人工作台
cd /d "%~dp0"

echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launcher.ps1" -Action start
if errorlevel 1 (
    echo.
    pause
)

endlocal
