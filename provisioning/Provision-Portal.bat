@echo off
REM Windows double-click entry point. Double-click to provision a Portal.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0provision.ps1"
echo.
pause
