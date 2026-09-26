@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_GPT6_CODE_GPT56_REVIEW_V9_2.ps1" -MaxCycles 0 -ResumeLatestReview
echo.
echo Exit code: %ERRORLEVEL%
pause
