@echo off
title 一键把游戏送到手机

rem 优先用 PowerShell 7 (pwsh)，没有就用系统自带的 5.1
set "PS=powershell"
where pwsh >nul 2>&1 && set "PS=pwsh"

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0一键送到手机.ps1"

if errorlevel 1 pause
