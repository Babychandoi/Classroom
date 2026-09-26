@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_ALL3_SOL_FINAL_V8_2.ps1" -MaxCycles 0 -ClaudeBypassPermissions
echo.
echo Exit code: %ERRORLEVEL%
pause
