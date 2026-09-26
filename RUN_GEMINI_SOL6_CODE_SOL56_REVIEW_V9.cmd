@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_GEMINI_SOL6_CODE_SOL56_REVIEW_V9.ps1" -MaxCycles 0
echo.
echo Exit code: %ERRORLEVEL%
pause
