@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_ALL3_FALLBACK_V8_1.ps1" -MaxCycles 0 -ClaudeBypassPermissions
echo.
echo Exit code: %ERRORLEVEL%
pause
