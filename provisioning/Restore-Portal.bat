@echo off
REM Windows double-click entry point. Double-click to undo provisioning.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0provision.ps1" -Restore
echo.
pause
