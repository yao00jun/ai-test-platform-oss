@echo off
chcp 65001 >nul
cd /d "%~dp0"
title AI 测试平台 - 查看状态
where pwsh >nul 2>nul
if errorlevel 1 (
  echo 本机还没有 PowerShell 7，正在通过 winget 安装（只需一次）……
  winget install --id Microsoft.PowerShell -e --source winget --accept-source-agreements --accept-package-agreements
  if errorlevel 1 (
    echo 自动安装失败。请到 https://aka.ms/powershell 下载安装 PowerShell 7，然后再双击本文件。
    pause
    exit /b 1
  )
  set "PATH=%ProgramFiles%\PowerShell\7;%PATH%"
)
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\aitest.ps1" status %*
echo.
pause
