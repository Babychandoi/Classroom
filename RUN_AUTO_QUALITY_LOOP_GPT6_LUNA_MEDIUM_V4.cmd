@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_GPT6_LUNA_MEDIUM_V4.ps1" -SkipInitialImplementation -MaxCycles 50
echo.
echo Exit code: %ERRORLEVEL%
pause
