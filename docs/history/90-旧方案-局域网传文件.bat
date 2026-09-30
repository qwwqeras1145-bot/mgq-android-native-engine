@echo off
title 局域网传文件

set "PS=powershell"
where pwsh >nul 2>&1 && set "PS=pwsh"

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0局域网传文件.ps1"

if errorlevel 1 pause
