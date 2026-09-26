@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_GENERIC_V6.ps1" -SkipInitialImplementation -MaxCycles 0 -ImplementModel "google/antigravity-gemini-3.8-flash" -QaModel "google/antigravity-gemini-3.8-flash" -ReviewModel "google/antigravity-claude-opus-4-6-thinking"
echo.
echo Exit code: %ERRORLEVEL%
pause
