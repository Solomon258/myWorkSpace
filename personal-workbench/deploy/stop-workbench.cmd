@echo off
chcp 936 >nul
setlocal
title 个人工作台 - 停止
cd /d "%~dp0"

echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launcher.ps1" -Action stop
echo.
pause

endlocal
