param(
    [string]$ProjectRoot = "D:\ClassRoom",
    [string]$RequirementsFile = "Bo-tai-lieu-he-thong-lop-hoc-v0.1.md",
    [string]$ImplementModel = "google/antigravity-gemini-3.8-flash",
    [string]$ReviewModel = "openai/gpt-6-luna",
    [string]$ReviewVariant = "medium",
    [int]$MaxCycles = 20,
    [switch]$SkipInitialImplementation
)

$ErrorActionPreference = "Continue"
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

    return (Join-Path $ProjectRoot $PathValue)
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

function Resolve-OpenCodeCommand {
    # Prefer the real native executable. This avoids the npm PowerShell shim
    # turning native stderr into a PowerShell NativeCommandError.
    $native = Get-Command opencode.exe -ErrorAction SilentlyContinue
    if ($native) {
        return $native.Source
    }

    $psShim = Get-Command opencode.ps1 -ErrorAction SilentlyContinue
    if ($psShim) {
        $shimDir = Split-Path -Parent $psShim.Source
        $candidate = Join-Path $shimDir "node_modules\opencode-ai\bin\opencode.exe"

        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }

        return $psShim.Source
    }

    $cmdShim = Get-Command opencode.cmd -ErrorAction SilentlyContinue
    if ($cmdShim) {
        return $cmdShim.Source
    }

    throw "Cannot find OpenCode. Ensure 'opencode' works from this terminal."
}

$OpenCodeCommand = Resolve-OpenCodeCommand

function Invoke-OpenCode {
    param(
        [Parameter(Mandatory=$true)][string]$Model,
        [Parameter(Mandatory=$true)][string]$PromptText,
        [Parameter(Mandatory=$true)][string]$LogFile,
        [string]$Variant = "",
        [string[]]$AttachFiles = @()
    )

    $opencodeArgs = @(
        "run",
        "--auto",
        "-m",
        $Model
    )

    if (-not [string]::IsNullOrWhiteSpace($Variant)) {
        $opencodeArgs += @("--variant", $Variant)
    }

    $opencodeArgs += $PromptText

    foreach ($file in $AttachFiles) {
        if (-not [string]::IsNullOrWhiteSpace($file) -and (Test-Path -LiteralPath $file)) {
            $opencodeArgs += @("--file", $file)
        }
    }

    if (Test-Path -LiteralPath $LogFile) {
        Remove-Item -LiteralPath $LogFile -Force
    }

    Write-Host ""
    Write-Host "OPENCODE: $OpenCodeCommand"
    Write-Host "MODEL   : $Model"
    if (-not [string]::IsNullOrWhiteSpace($Variant)) {
        Write-Host "VARIANT : $Variant"
    }
    Write-Host "LOG     : $LogFile"
    Write-Host ""

    $global:LASTEXITCODE = 0

    # PowerShell 5.1 wraps stderr from native programs as NativeCommandError.
    # Keep stderr visible/logged, but do not let that wrapper error terminate
    # the automation before we can inspect OpenCode's real exit code/output.
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"

    try {
        & $OpenCodeCommand @opencodeArgs 2>&1 |
            ForEach-Object {
                $line = $_.ToString()
                Write-Host $line
                Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
            }

        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }

    if ($null -eq $exitCode) {
        $exitCode = 1
    }

    if ($exitCode -ne 0) {
        Write-Warning "OpenCode exited with code $exitCode. Check log: $LogFile"
    }

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
    $escapedMarker = [Regex]::Escape($Marker)
    $allowedPattern = ($Allowed | ForEach-Object { [Regex]::Escape($_) }) -join "|"

    $pattern = "(?im)^\s*" + $escapedMarker + "\s*:\s*(" + $allowedPattern + ")\s*$"
    $matches = [Regex]::Matches($content, $pattern)

    if ($matches.Count -eq 0) {
        return $null
    }

    return $matches[$matches.Count - 1].Groups[1].Value.ToUpperInvariant()
}

function Save-Status {
    param(
        [string]$State,
        [int]$Cycle,
        [string]$Details = ""
    )

    $statusFile = Join-Path $ProjectRoot "docs\AUTO_QUALITY_LOOP_STATUS.md"
    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz")

    $lines = @(
        "# Auto Quality Loop Status",
        "",
        "- State: **$State**",
        "- Cycle: **$Cycle**",
        "- Updated: **$timestamp**",
        "- Implementation orchestrator: $ImplementModel",
        "- Review model: $ReviewModel",
        "- Review variant: $ReviewVariant",
        "- Reviewer fallback: disabled",
        "",
        $Details
    )

    Set-Content -LiteralPath $statusFile -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
}

function Invoke-GptReview {
    param(
        [Parameter(Mandatory=$true)][string]$PromptText,
        [Parameter(Mandatory=$true)][string]$LogFile,
        [Parameter(Mandatory=$true)][string]$VerdictMarker,
        [Parameter(Mandatory=$true)][string[]]$AllowedVerdicts,
        [string[]]$AttachFiles = @()
    )

    $exitCode = Invoke-OpenCode `
        -Model $ReviewModel `
        -Variant $ReviewVariant `
        -PromptText $PromptText `
        -LogFile $LogFile `
        -AttachFiles $AttachFiles

    if ($exitCode -ne 0) {
        return @{
            ExitCode = $exitCode
            Verdict = $null
            Model = $ReviewModel
            LogFile = $LogFile
        }
    }

    $verdict = Get-LastVerdict `
        -LogFile $LogFile `
        -Marker $VerdictMarker `
        -Allowed $AllowedVerdicts

    return @{
        ExitCode = 0
        Verdict = $verdict
        Model = $ReviewModel
        LogFile = $LogFile
    }
}

function Get-CodeReviewPrompt {
    return @"
You are an INDEPENDENT SENIOR SOFTWARE CODE REVIEWER.

ROLE SEPARATION:
- You are NOT the implementation agent.
- Do NOT edit application source code.
- Do NOT trust README, IMPLEMENTATION_STATUS.md, QUALITY_GATE.md, REVIEW_FINDINGS.md, or previous APPROVE claims as proof.
- Inspect the CURRENT repository state yourself.

Project root: $ProjectRoot

Review requirements:
1. Read the attached requirements document.
2. Inspect the real backend, frontend, infra, migrations, tests, and configuration.
3. Check Docker-first compliance.
4. Check Java and Spring Boot versions against the requirements.
5. Check authentication, authorization, RBAC, IDOR, cross-user, and cross-class access.
6. Check FREE, PRO, expired, entitlement, and product access boundaries.
7. Check exam audience, answer leakage, attempts, autosave, submit idempotency, grading, and result publishing.
8. Check commerce, mock payment flow, webhook idempotency, refund, revoke, and entitlement consistency.
9. Check media permission and presigned URL behavior.
10. Check segment parsing and injection safety.
11. Check outbox and projection consistency.
12. Check frontend is wired to real APIs rather than fake success paths.
13. Inspect tests and judge whether they prove required behavior.
14. Run appropriate Docker verification commands where useful.
15. A passing compile or typecheck alone is NOT sufficient.

If there is any material correctness, security, requirement, test, or integration gap that can still be fixed, verdict must be REJECT.

Return concrete findings with severity, file/module, evidence, and required fix.

The VERY LAST non-empty line MUST be exactly one of:
FINAL_VERDICT: APPROVE
FINAL_VERDICT: REJECT
"@
}

function Get-QaPrompt {
    return @"
You are an INDEPENDENT LIVE QA EXECUTOR.

Do NOT edit application source code.

Verify the RUNNING Docker system through real commands and user flows.

Required checks:
- docker compose status and health
- backend health endpoint
- frontend HTTP response
- authentication and login
- unauthorized access behavior
- OWNER, STAFF, FREE, PRO, and EXPIRED boundaries where available
- classroom access
- learning and course flow
- exam flow
- store, order, mock payment, and entitlement flow
- Studio permission boundaries
- forbidden, expired, not-found, pending, paid, failed, and refunded states where relevant
- real HTTP/API interactions where practical

Do not claim PASS for flows you did not actually exercise.

The VERY LAST non-empty line MUST be exactly one of:
QA_VERDICT: PASS
QA_VERDICT: FAIL
"@
}

function Get-FinalGatePrompt {
    return @"
You are the FINAL INDEPENDENT QUALITY GATE.

You are NOT the implementation agent and NOT the QA executor.
Do NOT edit application source code.
Do NOT blindly trust previous reports.

Independently determine whether the current repository is genuinely complete against the attached requirements.

Verify at minimum:
- unresolved review findings
- Docker build and test evidence
- backend and frontend correctness
- security and permission boundaries
- required architecture and versions
- migrations and seed
- critical user journeys
- QA evidence
- requirement mismatches
- fake or missing evidence
- known blockers that are still fixable

Any material unresolved issue means REJECT.

The VERY LAST non-empty line MUST be exactly one of:
FINAL_GATE: APPROVE
FINAL_GATE: REJECT
"@
}

Set-Location -LiteralPath $ProjectRoot

$requirementsPath = Resolve-ProjectPath $RequirementsFile
Ensure-Exists -PathValue $requirementsPath -Label "Requirements file"

$automationDir = Join-Path $ProjectRoot "automation"
$reviewDir = Join-Path $automationDir "reviews"
$qaDir = Join-Path $automationDir "qa"
$gateDir = Join-Path $automationDir "gates"
$runDir = Join-Path $automationDir "runs"
$docsDir = Join-Path $ProjectRoot "docs"

New-Item -ItemType Directory -Force -Path $reviewDir | Out-Null
New-Item -ItemType Directory -Force -Path $qaDir | Out-Null
New-Item -ItemType Directory -Force -Path $gateDir | Out-Null
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
New-Item -ItemType Directory -Force -Path $docsDir | Out-Null

Write-Section "AUTO QUALITY LOOP START"
Write-Host "Project root          : $ProjectRoot"
Write-Host "Implementation model  : $ImplementModel"
Write-Host "Review/final gate      : $ReviewModel"
Write-Host "Review variant         : $ReviewVariant"
Write-Host "Max cycles             : $MaxCycles"
Write-Host "Skip initial implement : $SkipInitialImplementation"

if (-not $SkipInitialImplementation) {
    Write-Section "INITIAL IMPLEMENTATION"

    $masterPromptPath = Join-Path $ProjectRoot "MASTER_PROMPT_FINAL_CORRECTED.txt"
    Ensure-Exists -PathValue $masterPromptPath -Label "Master prompt"

    Save-Status -State "INITIAL_IMPLEMENTATION" -Cycle 0

    $initialPrompt = Get-Content -Raw -Encoding UTF8 -LiteralPath $masterPromptPath
    $initialLog = Join-Path $runDir "initial-implementation.log"

    $initialExit = Invoke-OpenCode `
        -Model $ImplementModel `
        -PromptText $initialPrompt `
        -LogFile $initialLog `
        -AttachFiles @($requirementsPath)

    if ($initialExit -ne 0) {
        Save-Status -State "BLOCKED_INITIAL_IMPLEMENTATION" -Cycle 0 -Details "See automation/runs/initial-implementation.log"
        throw "Initial implementation failed. See: $initialLog"
    }
}

$cycle = 1

while ($cycle -le $MaxCycles) {
    Write-Section "CYCLE $cycle - GPT-6 LUNA MEDIUM CODE REVIEW"
    Save-Status -State "CODE_REVIEW" -Cycle $cycle

    $reviewPrompt = Get-CodeReviewPrompt
    $reviewLog = Join-Path $reviewDir ("code-review-{0:D2}-gpt6-luna-medium.log" -f $cycle)

    $review = Invoke-GptReview `
        -PromptText $reviewPrompt `
        -LogFile $reviewLog `
        -VerdictMarker "FINAL_VERDICT" `
        -AllowedVerdicts @("APPROVE", "REJECT") `
        -AttachFiles @($requirementsPath)

    if ($review.ExitCode -ne 0 -or -not $review.Verdict) {
        Save-Status -State "BLOCKED_REVIEW" -Cycle $cycle -Details "GPT-6 Luna medium failed or returned no valid FINAL_VERDICT. See: $reviewLog"
        throw "GPT-6 Luna medium review failed or emitted no valid FINAL_VERDICT."
    }

    Write-Host ""
    Write-Host "REVIEW MODEL   : $($review.Model)"
    Write-Host "REVIEW VERDICT : $($review.Verdict)"
    Write-Host "REVIEW LOG     : $reviewLog"

    if ($review.Verdict -eq "REJECT") {
        Write-Section "CYCLE $cycle - GEMINI FIX REVIEW FINDINGS"
        Save-Status -State "FIXING_REVIEW_FINDINGS" -Cycle $cycle -Details "Review log: $reviewLog"

        $fixPrompt = @"
You are the IMPLEMENTATION AND FIX agent.

Fix ALL actionable findings in the attached independent GPT-6 Luna review report.

Rules:
- Work autonomously.
- Read the actual source before editing.
- Fix root causes.
- Do not fake completion.
- Do not weaken security, validation, compiler checks, TypeScript strictness, or tests just to make them green.
- Preserve Docker-first operation.
- Run appropriate Docker build and tests after changes.
- Update docs/REVIEW_FINDINGS.md and docs/QUALITY_GATE.md truthfully.
- Do NOT mark the project final DONE.
- A fresh independent GPT-6 Luna review will run after you finish.

Continue until all actionable findings are addressed as far as technically possible.
"@

        $fixLog = Join-Path $runDir ("fix-after-review-{0:D2}.log" -f $cycle)

        $fixExit = Invoke-OpenCode `
            -Model $ImplementModel `
            -PromptText $fixPrompt `
            -LogFile $fixLog `
            -AttachFiles @($requirementsPath, $reviewLog)

        if ($fixExit -ne 0) {
            Save-Status -State "BLOCKED_FIX" -Cycle $cycle -Details "See: $fixLog"
            throw "Implementation fix failed."
        }

        $cycle++
        continue
    }

    Write-Section "CYCLE $cycle - GEMINI LIVE QA"
    Save-Status -State "LIVE_QA" -Cycle $cycle

    $qaPrompt = Get-QaPrompt
    $qaLog = Join-Path $qaDir ("qa-{0:D2}-gemini.log" -f $cycle)

    $qaExit = Invoke-OpenCode `
        -Model $ImplementModel `
        -PromptText $qaPrompt `
        -LogFile $qaLog `
        -AttachFiles @($requirementsPath, $reviewLog)

    $qaVerdict = $null
    if ($qaExit -eq 0) {
        $qaVerdict = Get-LastVerdict `
            -LogFile $qaLog `
            -Marker "QA_VERDICT" `
            -Allowed @("PASS", "FAIL")
    }

    if ($qaExit -ne 0 -or -not $qaVerdict) {
        Save-Status -State "BLOCKED_QA" -Cycle $cycle -Details "QA failed or returned no valid QA_VERDICT. See: $qaLog"
        throw "Live QA failed or emitted no valid QA_VERDICT."
    }

    Write-Host ""
    Write-Host "QA VERDICT : $qaVerdict"
    Write-Host "QA LOG     : $qaLog"

    if ($qaVerdict -eq "FAIL") {
        Write-Section "CYCLE $cycle - GEMINI FIX QA FAILURES"
        Save-Status -State "FIXING_QA_FAILURES" -Cycle $cycle -Details "QA log: $qaLog"

        $fixQaPrompt = @"
You are the IMPLEMENTATION AND FIX agent.

The attached live QA report FAILED.

Fix every actionable QA failure in the real project.

Rules:
- Reproduce failures where practical.
- Fix root causes.
- Preserve security and Docker-first operation.
- Run appropriate Docker build and tests.
- Do not fake PASS.
- Do NOT mark final DONE.
- A fresh GPT-6 Luna review will run after fixes.
"@

        $fixQaLog = Join-Path $runDir ("fix-after-qa-{0:D2}.log" -f $cycle)

        $fixQaExit = Invoke-OpenCode `
            -Model $ImplementModel `
            -PromptText $fixQaPrompt `
            -LogFile $fixQaLog `
            -AttachFiles @($requirementsPath, $qaLog)

        if ($fixQaExit -ne 0) {
            Save-Status -State "BLOCKED_FIX_QA" -Cycle $cycle -Details "See: $fixQaLog"
            throw "Fix after QA failure failed."
        }

        $cycle++
        continue
    }

    Write-Section "CYCLE $cycle - GPT-6 LUNA MEDIUM FINAL GATE"
    Save-Status -State "FINAL_GATE" -Cycle $cycle

    $gatePrompt = Get-FinalGatePrompt
    $gateLog = Join-Path $gateDir ("final-gate-{0:D2}-gpt6-luna-medium.log" -f $cycle)

    $gate = Invoke-GptReview `
        -PromptText $gatePrompt `
        -LogFile $gateLog `
        -VerdictMarker "FINAL_GATE" `
        -AllowedVerdicts @("APPROVE", "REJECT") `
        -AttachFiles @($requirementsPath, $reviewLog, $qaLog)

    if ($gate.ExitCode -ne 0 -or -not $gate.Verdict) {
        Save-Status -State "BLOCKED_FINAL_GATE" -Cycle $cycle -Details "GPT-6 Luna medium final gate failed or returned no valid FINAL_GATE. See: $gateLog"
        throw "GPT-6 Luna medium final gate failed or emitted no valid FINAL_GATE."
    }

    Write-Host ""
    Write-Host "FINAL GATE MODEL   : $($gate.Model)"
    Write-Host "FINAL GATE VERDICT : $($gate.Verdict)"
    Write-Host "FINAL GATE LOG     : $gateLog"

    if ($gate.Verdict -eq "APPROVE") {
        Write-Section "QUALITY LOOP COMPLETE"

        $details = @(
            "Independent review: APPROVE",
            "Review model: $ReviewModel",
            "Review variant: $ReviewVariant",
            "Review log: $reviewLog",
            "",
            "Live QA: PASS",
            "QA log: $qaLog",
            "",
            "Final gate: APPROVE",
            "Final gate model: $ReviewModel",
            "Final gate variant: $ReviewVariant",
            "Final gate log: $gateLog"
        ) -join [Environment]::NewLine

        Save-Status -State "APPROVED" -Cycle $cycle -Details $details

        Write-Host ""
        Write-Host "APPROVED"
        Write-Host "Status file: docs\AUTO_QUALITY_LOOP_STATUS.md"
        exit 0
    }

    Write-Section "CYCLE $cycle - GEMINI FIX FINAL GATE FINDINGS"
    Save-Status -State "FIXING_FINAL_GATE_FINDINGS" -Cycle $cycle -Details "Final gate log: $gateLog"

    $fixGatePrompt = @"
You are the IMPLEMENTATION AND FIX agent.

The attached independent GPT-6 Luna FINAL GATE report REJECTED the current project.

Fix ALL actionable final-gate findings.

Rules:
- Inspect and fix the real source.
- Preserve Docker-first operation and security.
- Run relevant Docker build and tests.
- Do not fake evidence.
- Do NOT mark final DONE.
- After fixes, the automation will run a fresh GPT-6 Luna code review, Gemini live QA, and GPT-6 Luna final gate again.
"@

    $fixGateLog = Join-Path $runDir ("fix-after-final-gate-{0:D2}.log" -f $cycle)

    $fixGateExit = Invoke-OpenCode `
        -Model $ImplementModel `
        -PromptText $fixGatePrompt `
        -LogFile $fixGateLog `
        -AttachFiles @($requirementsPath, $gateLog)

    if ($fixGateExit -ne 0) {
        Save-Status -State "BLOCKED_FIX_GATE" -Cycle $cycle -Details "See: $fixGateLog"
        throw "Fix after final gate rejection failed."
    }

    $cycle++
}

Write-Section "MAX CYCLES REACHED"
Save-Status -State "MAX_CYCLES_REACHED" -Cycle $MaxCycles -Details "No final approval within $MaxCycles cycles. Check automation logs."
Write-Warning "Maximum cycle count reached without final approval."
exit 2
