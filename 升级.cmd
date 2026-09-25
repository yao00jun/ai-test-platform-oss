@echo off
chcp 65001 >nul
cd /d "%~dp0"
title AI 测试平台 - 升级
set "PWSH=pwsh"
where pwsh >nul 2>nul && goto :run
if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe" && goto :run
if exist "%~dp0.tools\pwsh\pwsh.exe" set "PWSH=%~dp0.tools\pwsh\pwsh.exe" && goto :run
echo 本机还没有 PowerShell 7。
echo   有网络：正在通过 winget 安装（只需一次，请在弹出的窗口里点“是”）……
echo   没网络：请在有网的电脑打开 https://aka.ms/powershell 下载 PowerShell-7.x-win-x64.msi，拷过来安装后再双击本文件。
winget install --id Microsoft.PowerShell -e --source winget --accept-source-agreements --accept-package-agreements
if errorlevel 1 (
  echo 自动安装没有成功。请按上面“没网络”的办法手动安装 PowerShell 7。
  pause
  exit /b 1
)
set "PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe"
:run
echo 请把正在使用的旧安装文件夹拖到此窗口，然后按回车。
set /p "TARGET=旧安装目录："
set "TARGET=%TARGET:"=%"
if "%TARGET%"=="" (
  echo 没有输入旧安装目录，升级已取消。
  pause
  exit /b 1
)
"%PWSH%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\aitest.ps1" upgrade -Target "%TARGET%"
if errorlevel 1 (
  echo.
  echo 上面有错误信息。旧安装目录没有被自动清理，请查看日志后再处理。
  pause
)
