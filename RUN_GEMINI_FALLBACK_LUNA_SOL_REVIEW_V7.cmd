@echo off
cd /d D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File ".\AUTO_QUALITY_LOOP_GEMINI_FALLBACK_LUNA_SOL_REVIEW_V7.ps1" -SkipInitialImplementation -MaxCycles 0 -ResumeFixReviewLog ".\automation\reviews\code-review-11-attempt1-openai-gpt-6-sol.log"
echo.
echo Exit code: %ERRORLEVEL%
pause
