param(
    [string]$ProjectRoot = "D:\ClassRoom",
    [string]$MasterPrompt = "MASTER_PROMPT_FINAL_CORRECTED.txt",
    [string]$RequirementsFile = "Bo-tai-lieu-he-thong-lop-hoc-v0.1.md",

    [string]$ImplementModel = "google/antigravity-gemini-3.8-flash",

    [string]$ReviewModelPrimary = "openai/gpt-6-luna",
    [string]$ReviewModelFallback = "",
    [string]$ReviewVariant = "medium",

    [int]$MaxCycles = 20,

    [switch]$SkipInitialImplementation
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Write-Section {
    param([string]$Text)
    Write-Host ""
    Write-Host ("=" * 78)
    Write-Host $Text
    Write-Host ("=" * 78)
}

function Resolve-ProjectPath {
    param([string]$PathValue)

    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }

    return Join-Path $ProjectRoot $PathValue
}

function Ensure-Exists {
    param(
        [string]$PathValue,
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $PathValue)) {
        throw "$Label not found: $PathValue"
    }
}

function Invoke-OpenCode {
    param(
        [Parameter(Mandatory=$true)][string]$Model,
        [Parameter(Mandatory=$true)][string]$PromptText,
        [Parameter(Mandatory=$true)][string]$LogFile,
        [string]$Variant = "",
        [string[]]$AttachFiles = @()
    )

    $args = @(
        "run",
        "--auto",
        "-m", $Model
    )

    if (-not [string]::IsNullOrWhiteSpace($Variant)) {
        $args += @("--variant", $Variant)
    }

    $args += @($PromptText)

    foreach ($file in $AttachFiles) {
        if (-not [string]::IsNullOrWhiteSpace($file) -and (Test-Path -LiteralPath $file)) {
            $args += @("--file", $file)
        }
    }

    Write-Host ""
    Write-Host "MODEL: $Model"
    if ($Variant) {
        Write-Host "VARIANT: $Variant"
    }
    Write-Host "LOG: $LogFile"
    Write-Host ""

    $global:LASTEXITCODE = 0
    & opencode @args 2>&1 | Tee-Object -FilePath $LogFile
    $exitCode = $LASTEXITCODE

    return [int]$exitCode
}

function Get-LastVerdict {
    param(
        [string]$LogFile,
        [string]$Marker,
        [string[]]$Allowed
    )

    if (-not (Test-Path -LiteralPath $LogFile)) {
        return $null
    }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $LogFile
    $escaped = [Regex]::Escape($Marker)
    $allowedPattern = ($Allowed | ForEach-Object { [Regex]::Escape($_) }) -join "|"

    $matches = [Regex]::Matches(
        $content,
        "(?im)^\s*$escaped\s*:\s*($allowedPattern)\s*$"
    )

    if ($matches.Count -eq 0) {
        return $null
    }

    return $matches[$matches.Count - 1].Groups[1].Value.ToUpperInvariant()
}

function Invoke-OpenAIReviewer {
    param(
        [Parameter(Mandatory=$true)][string]$PromptText,
        [Parameter(Mandatory=$true)][string]$PrimaryLog,
        [Parameter(Mandatory=$true)][string]$FallbackLog,
        [Parameter(Mandatory=$true)][string]$VerdictMarker,
        [Parameter(Mandatory=$true)][string[]]$AllowedVerdicts,
        [string[]]$AttachFiles = @()
    )

    $exitCode = Invoke-OpenCode `
        -Model $ReviewModelPrimary `
        -Variant $ReviewVariant `
        -PromptText $PromptText `
        -LogFile $PrimaryLog `
        -AttachFiles $AttachFiles

    if ($exitCode -ne 0) {
        return @{
            ExitCode = $exitCode
            Verdict = $null
            Model = $ReviewModelPrimary
            LogFile = $PrimaryLog
        }
    }

    $verdict = Get-LastVerdict `
        -LogFile $PrimaryLog `
        -Marker $VerdictMarker `
        -Allowed $AllowedVerdicts

    return @{
        ExitCode = 0
        Verdict = $verdict
        Model = $ReviewModelPrimary
        LogFile = $PrimaryLog
    }
}

function Save-Status {
    param(
        [string]$State,
        [int]$Cycle,
        [string]$Details = ""
    )

    $statusFile = Join-Path $ProjectRoot "docs\AUTO_QUALITY_LOOP_STATUS.md"
    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz")

    $body = @"
# Auto Quality Loop Status

- State: **$State**
- Cycle: **$Cycle**
- Updated: **$timestamp**
- Implementation orchestrator: `$ImplementModel`
- Review primary: `$ReviewModelPrimary`
- Review fallback: **DISABLED**
- Review variant: `$ReviewVariant`

$Details
"@

    Set-Content -LiteralPath $statusFile -Value $body -Encoding UTF8
}

function Get-CommonReviewerPrompt {
    return @"
You are an INDEPENDENT SENIOR SOFTWARE REVIEWER.

CRITICAL ROLE SEPARATION:
- You are NOT the implementation agent.
- Do NOT trust README, IMPLEMENTATION_STATUS.md, QUALITY_GATE.md, REVIEW_FINDINGS.md, or previous APPROVE claims as proof.
- Do NOT edit application source code.
- You may run read-only inspection commands and Docker build/test/verification commands.
- Review the CURRENT repository state yourself.

Project root: $ProjectRoot

Required verification:
1. Read the attached requirements.
2. Inspect the real backend/frontend/infra source.
3. Check Docker-first compliance.
4. Check Java/Spring Boot version against the requirement.
5. Check auth, RBAC, IDOR, cross-user and cross-class access.
6. Check FREE/PRO/expired/entitlement boundaries.
7. Check exam audience, answer leakage, submit/idempotency and grading.
8. Check commerce/payment mock/webhook/idempotency/refund behavior.
9. Check media permission and presigned URL handling.
10. Check segment safety/injection.
11. Check outbox/projection consistency.
12. Check frontend is wired to real APIs rather than fake success paths.
13. Inspect tests and determine whether they actually prove required behavior.
14. Run appropriate Docker commands when needed.
15. Treat a passing compile/typecheck alone as insufficient evidence.

If there is any material correctness, security, requirement, test, or integration gap that can still be fixed, verdict must be REJECT.

Return a concise but concrete findings report with:
- severity
- file/module
- evidence
- required fix

The VERY LAST non-empty line MUST be exactly one of:
FINAL_VERDICT: APPROVE
FINAL_VERDICT: REJECT
"@
}

Set-Location -LiteralPath $ProjectRoot

$masterPromptPath = Resolve-ProjectPath $MasterPrompt
$requirementsPath = Resolve-ProjectPath $RequirementsFile

Ensure-Exists -PathValue $masterPromptPath -Label "Master prompt"
Ensure-Exists -PathValue $requirementsPath -Label "Requirements file"

$reviewDir = Join-Path $ProjectRoot "automation\reviews"
$qaDir = Join-Path $ProjectRoot "automation\qa"
$gateDir = Join-Path $ProjectRoot "automation\gates"
$runDir = Join-Path $ProjectRoot "automation\runs"

New-Item -ItemType Directory -Force -Path $reviewDir | Out-Null
New-Item -ItemType Directory -Force -Path $qaDir | Out-Null
New-Item -ItemType Directory -Force -Path $gateDir | Out-Null
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot "docs") | Out-Null

Write-Section "AUTO QUALITY LOOP START"
Write-Host "Project: $ProjectRoot"
Write-Host "Max cycles: $MaxCycles"
Write-Host "Implementation model: $ImplementModel"
Write-Host "Reviewer primary: $ReviewModelPrimary ($ReviewVariant)"
Write-Host "Reviewer fallback: DISABLED"

if (-not $SkipInitialImplementation) {
    Write-Section "INITIAL IMPLEMENTATION / AUDIT"

    Save-Status -State "INITIAL_IMPLEMENTATION" -Cycle 0

    $initialPrompt = Get-Content -Raw -Encoding UTF8 -LiteralPath $masterPromptPath
    $initialLog = Join-Path $runDir "initial-implementation.log"

    $initialExit = Invoke-OpenCode `
        -Model $ImplementModel `
        -PromptText $initialPrompt `
        -LogFile $initialLog `
        -AttachFiles @($requirementsPath)

    if ($initialExit -ne 0) {
        Save-Status `
            -State "BLOCKED_INITIAL_IMPLEMENTATION" `
            -Cycle 0 `
            -Details "OpenCode exited with code $initialExit. See `automation/runs/initial-implementation.log`."

        throw "Initial implementation failed. See $initialLog"
    }
}

$cycle = 1

while ($cycle -le $MaxCycles) {
    Write-Section "CYCLE $cycle - INDEPENDENT GPT REVIEW"
    Save-Status -State "CODE_REVIEW" -Cycle $cycle

    $reviewPrompt = Get-CommonReviewerPrompt

    $reviewPrimaryLog = Join-Path $reviewDir ("code-review-{0:D2}-gpt6-luna-medium.log" -f $cycle)
    $reviewFallbackLog = Join-Path $reviewDir ("code-review-{0:D2}-unused.log" -f $cycle)

    $review = Invoke-OpenAIReviewer `
        -PromptText $reviewPrompt `
        -PrimaryLog $reviewPrimaryLog `
        -FallbackLog $reviewFallbackLog `
        -VerdictMarker "FINAL_VERDICT" `
        -AllowedVerdicts @("APPROVE", "REJECT") `
        -AttachFiles @($requirementsPath)

    if ($review.ExitCode -ne 0 -or -not $review.Verdict) {
        Save-Status `
            -State "BLOCKED_REVIEW" `
            -Cycle $cycle `
            -Details "GPT-6 Luna medium did not produce a valid verdict. Log: $($review.LogFile)"

        throw "Independent GPT review failed or emitted no valid verdict."
    }

    Write-Host ""
    Write-Host "REVIEW MODEL: $($review.Model)"
    Write-Host "REVIEW VERDICT: $($review.Verdict)"
    Write-Host "REVIEW LOG: $($review.LogFile)"

    if ($review.Verdict -eq "REJECT") {
        Write-Section "CYCLE $cycle - ANTIGRAVITY FIX"

        Save-Status `
            -State "FIXING_REVIEW_FINDINGS" `
            -Cycle $cycle `
            -Details "Reviewer: $($review.Model)`n`nReview log: `$($review.LogFile)`"

        $fixPrompt = @"
You are the IMPLEMENTATION/FIX agent.

Fix ALL actionable findings in the attached independent review report.

Rules:
- Work autonomously.
- Read the actual source before editing.
- Do not fake completion.
- Do not weaken security, validation, compiler checks, TypeScript strictness, or tests just to make them green.
- Preserve Docker-first operation.
- Run appropriate Docker build/tests after changes.
- Update docs/REVIEW_FINDINGS.md and docs/QUALITY_GATE.md truthfully.
- Do NOT mark the project final DONE. A fresh independent GPT review will run after you finish.
- If a finding is invalid, prove it with concrete source/test evidence rather than merely disagreeing.

Continue until all findings are addressed as far as technically possible.
"@

        $fixLog = Join-Path $runDir ("fix-after-review-{0:D2}.log" -f $cycle)

        $fixExit = Invoke-OpenCode `
            -Model $ImplementModel `
            -PromptText $fixPrompt `
            -LogFile $fixLog `
            -AttachFiles @($requirementsPath, $review.LogFile)

        if ($fixExit -ne 0) {
            Save-Status `
                -State "BLOCKED_FIX" `
                -Cycle $cycle `
                -Details "Implementation/fix exited with code $fixExit. See `$fixLog`."

            throw "Implementation fix failed."
        }

        $cycle++
        continue
    }

    Write-Section "CYCLE $cycle - LIVE QA WITH GEMINI"
    Save-Status -State "LIVE_QA" -Cycle $cycle

    $qaPrompt = @"
You are an INDEPENDENT LIVE QA EXECUTOR.

Do not edit application source code.

The independent code review has APPROVED the current source state.
Now verify the RUNNING Docker system through real commands and user flows.

Required:
- docker compose status/health
- backend health
- frontend HTTP response
- authentication/login
- unauthorized access behavior
- OWNER / STAFF / FREE / PRO / EXPIRED boundaries where available
- classroom access
- learning/course flow
- exam flow
- store/order/payment mock/entitlement flow
- Studio permission boundaries
- relevant error/forbidden/expired states
- use real HTTP/API interactions where practical
- do not claim PASS for flows you did not actually exercise

If a critical or high-impact flow is broken, QA must FAIL.

The VERY LAST non-empty line MUST be exactly one of:
QA_VERDICT: PASS
QA_VERDICT: FAIL
"@

    $qaLog = Join-Path $qaDir ("qa-{0:D2}-gemini.log" -f $cycle)

    $qaExit = Invoke-OpenCode `
        -Model $ImplementModel `
        -PromptText $qaPrompt `
        -LogFile $qaLog `
        -AttachFiles @($requirementsPath, $review.LogFile)

    $qaVerdict = $null
    if ($qaExit -eq 0) {
        $qaVerdict = Get-LastVerdict `
            -LogFile $qaLog `
            -Marker "QA_VERDICT" `
            -Allowed @("PASS", "FAIL")
    }

    if ($qaExit -ne 0 -or -not $qaVerdict) {
        Save-Status `
            -State "BLOCKED_QA" `
            -Cycle $cycle `
            -Details "QA did not produce a valid verdict. See `$qaLog`."

        throw "Live QA failed to complete or emitted no valid QA_VERDICT."
    }

    Write-Host ""
    Write-Host "QA VERDICT: $qaVerdict"
    Write-Host "QA LOG: $qaLog"

    if ($qaVerdict -eq "FAIL") {
        Write-Section "CYCLE $cycle - FIX QA FAILURES"

        Save-Status `
            -State "FIXING_QA_FAILURES" `
            -Cycle $cycle `
            -Details "QA log: `$qaLog`"

        $fixQaPrompt = @"
You are the IMPLEMENTATION/FIX agent.

The attached live QA report FAILED.
Fix every actionable QA failure in the real project.

Rules:
- Inspect source and reproduce the failures.
- Fix root causes, not symptoms.
- Preserve security and Docker-first operation.
- Run appropriate Docker build/tests.
- Do not fake PASS.
- Do not mark final DONE; a fresh independent GPT code review will run after fixes.
"@

        $fixQaLog = Join-Path $runDir ("fix-after-qa-{0:D2}.log" -f $cycle)

        $fixQaExit = Invoke-OpenCode `
            -Model $ImplementModel `
            -PromptText $fixQaPrompt `
            -LogFile $fixQaLog `
            -AttachFiles @($requirementsPath, $qaLog)

        if ($fixQaExit -ne 0) {
            Save-Status -State "BLOCKED_FIX_QA" -Cycle $cycle -Details "See `$fixQaLog`."
            throw "Fix after QA failure failed."
        }

        $cycle++
        continue
    }

    Write-Section "CYCLE $cycle - GPT FINAL GATE"
    Save-Status -State "FINAL_GATE" -Cycle $cycle

    $gatePrompt = @"
You are the FINAL INDEPENDENT QUALITY GATE.

You are NOT the implementation agent and NOT the QA executor.
Do NOT edit application source code.

Independently verify whether the current repository is genuinely ready to be considered complete against the attached requirements.

You must inspect current source/evidence yourself. Do not blindly trust previous reports.

At minimum verify:
- unresolved review findings
- Docker build/test evidence
- backend/frontend correctness
- security and permission boundaries
- required architecture and versions
- migration/seed
- critical user journeys
- QA evidence
- requirement mismatches
- fake/missing evidence
- known blockers that are still fixable

A material unresolved issue means REJECT.

The VERY LAST non-empty line MUST be exactly one of:
FINAL_GATE: APPROVE
FINAL_GATE: REJECT
"@

    $gatePrimaryLog = Join-Path $gateDir ("final-gate-{0:D2}-gpt6-luna-medium.log" -f $cycle)
    $gateFallbackLog = Join-Path $gateDir ("final-gate-{0:D2}-unused.log" -f $cycle)

    $gate = Invoke-OpenAIReviewer `
        -PromptText $gatePrompt `
        -PrimaryLog $gatePrimaryLog `
        -FallbackLog $gateFallbackLog `
        -VerdictMarker "FINAL_GATE" `
        -AllowedVerdicts @("APPROVE", "REJECT") `
        -AttachFiles @($requirementsPath, $review.LogFile, $qaLog)

    if ($gate.ExitCode -ne 0 -or -not $gate.Verdict) {
        Save-Status `
            -State "BLOCKED_FINAL_GATE" `
            -Cycle $cycle `
            -Details "GPT-6 Luna medium final gate did not produce a valid verdict. See `$($gate.LogFile)`."

        throw "Final gate failed or emitted no valid verdict."
    }

    Write-Host ""
    Write-Host "FINAL GATE MODEL: $($gate.Model)"
    Write-Host "FINAL GATE VERDICT: $($gate.Verdict)"
    Write-Host "FINAL GATE LOG: $($gate.LogFile)"

    if ($gate.Verdict -eq "APPROVE") {
        Write-Section "QUALITY LOOP COMPLETE"

        Save-Status `
            -State "APPROVED" `
            -Cycle $cycle `
            -Details @"
Independent review: APPROVE
Reviewer model: $($review.Model)
Review log: `$($review.LogFile)`

Live QA: PASS
QA log: `$qaLog`

Final gate: APPROVE
Final gate model: $($gate.Model)
Final gate log: `$($gate.LogFile)`
"@

        Write-Host "APPROVED."
        Write-Host "Independent review model: $($review.Model)"
        Write-Host "Final gate model: $($gate.Model)"
        Write-Host "Status: docs\AUTO_QUALITY_LOOP_STATUS.md"
        exit 0
    }

    Write-Section "CYCLE $cycle - FIX FINAL GATE REJECTION"

    Save-Status `
        -State "FIXING_FINAL_GATE_FINDINGS" `
        -Cycle $cycle `
        -Details "Final gate reviewer: $($gate.Model)`n`nGate log: `$($gate.LogFile)`"

    $fixGatePrompt = @"
You are the IMPLEMENTATION/FIX agent.

The attached independent FINAL GATE report REJECTED the current project.
Fix ALL actionable gate findings.

Rules:
- Inspect and fix the real source.
- Preserve Docker-first operation and security.
- Run relevant Docker build/tests.
- Do not fake evidence.
- Do not mark final DONE.
- After fixes, the automation will run a completely fresh GPT code review, then QA, then final gate again.
"@

    $fixGateLog = Join-Path $runDir ("fix-after-final-gate-{0:D2}.log" -f $cycle)

    $fixGateExit = Invoke-OpenCode `
        -Model $ImplementModel `
        -PromptText $fixGatePrompt `
        -LogFile $fixGateLog `
        -AttachFiles @($requirementsPath, $gate.LogFile)

    if ($fixGateExit -ne 0) {
        Save-Status -State "BLOCKED_FIX_GATE" -Cycle $cycle -Details "See `$fixGateLog`."
        throw "Fix after final gate rejection failed."
    }

    $cycle++
}

Write-Section "MAX CYCLES REACHED"
Save-Status `
    -State "MAX_CYCLES_REACHED" `
    -Cycle $MaxCycles `
    -Details "The project did not reach final APPROVE within $MaxCycles cycles. Review automation logs before continuing."

Write-Warning "Maximum cycle count reached without final approval."
exit 2
