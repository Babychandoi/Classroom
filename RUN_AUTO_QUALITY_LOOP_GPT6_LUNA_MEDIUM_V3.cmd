@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_GPT6_LUNA_MEDIUM_V3.ps1" -SkipInitialImplementation
echo.
echo Exit code: %ERRORLEVEL%
pause
