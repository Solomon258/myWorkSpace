@echo off
chcp 936 >nul
setlocal
title 个人工作台 - 备份
cd /d "%~dp0"

echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launcher.ps1" -Action backup
echo.
echo   提示：data 目录就是你的全部数据，也可以直接复制整个 data 文件夹另存。
echo.
pause

endlocal
