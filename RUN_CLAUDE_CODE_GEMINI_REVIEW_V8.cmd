@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_CLAUDE_CODE_GEMINI_REVIEW_V8.ps1" -MaxCycles 0
echo.
echo Exit code: %ERRORLEVEL%
pause
