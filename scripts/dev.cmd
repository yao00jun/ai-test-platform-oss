@echo off
chcp 65001 >nul
rem 双击 = 启动；也可 dev.cmd down / restart / status / logs
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev.ps1" %*
if errorlevel 1 pause
if "%~1"=="status" pause
