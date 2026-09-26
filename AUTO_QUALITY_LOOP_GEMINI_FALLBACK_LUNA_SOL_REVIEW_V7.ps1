param(
    [string]$ProjectRoot = "D:\ClassRoom",
    [string]$RequirementsFile = "Bo-tai-lieu-he-thong-lop-hoc-v0.1.md",

    # CODE / FIX primary + fallback
    [string]$ImplementModel = "google/antigravity-gemini-3.8-flash",
    [string]$ImplementVariant = "",
    [string]$ImplementFallbackModel = "openai/gpt-6-luna",
    [string]$ImplementFallbackVariant = "medium",

    # QA primary + fallback
    [string]$QaModel = "google/antigravity-gemini-3.8-flash",
    [string]$QaVariant = "",
    [string]$QaFallbackModel = "openai/gpt-6-luna",
    [string]$QaFallbackVariant = "medium",

    # Independent REVIEW / FINAL GATE
    [string]$ReviewModel = "openai/gpt-6-sol",
    [string]$ReviewVariant = "medium",

    # 0 = unlimited cycles.
    [int]$MaxCycles = 50,

    # Optional: resume directly from an existing REJECT review log.
    [string]$ResumeFixReviewLog = "",

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

function Convert-ToSafeName {
    param([string]$Value)
    $safe = $Value -replace '[^A-Za-z0-9._-]', '-'
    $safe = $safe -replace '-+', '-'
    return $safe.Trim('-')
}

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

function Test-ProviderUnavailable {
    param([string]$LogFile)

    if (-not (Test-Path -LiteralPath $LogFile)) {
        return $false
    }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $LogFile

    $patterns = @(
        'All\s+\d+\s+account\(s\)\s+rate-limited',
        'rate[- ]limited',
        'quota\s+resets',
        'quota\s+(exceeded|exhausted|reached)',
        'too\s+many\s+requests',
        'no\s+available\s+account',
        'provider\s+(is\s+)?unavailable',
        'model\s+(is\s+)?unavailable',
        'service\s+unavailable',
        '\bHTTP\s*429\b',
        '\b429\b.*(quota|rate|limit)',
        'resource\s+exhausted',
        'usage\s+limit\s+(has\s+been\s+)?reached'
    )

    foreach ($pattern in $patterns) {
        if ($content -match "(?i)$pattern") {
            return $true
        }
    }

    return $false
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

function Get-UniqueModels {
    param([string[]]$Models)

    $seen = @{}
    $result = @()

    foreach ($model in $Models) {
        if ([string]::IsNullOrWhiteSpace($model)) {
            continue
        }

        if (-not $seen.ContainsKey($model)) {
            $seen[$model] = $true
            $result += $model
        }
    }

    return $result
}

function Invoke-ModelChain {
    param(
        [Parameter(Mandatory=$true)][string[]]$Models,
        [Parameter(Mandatory=$true)][string]$PromptText,
        [Parameter(Mandatory=$true)][string]$LogBasePath,
        [string]$Variant = "",
        [hashtable]$VariantByModel = @{},
        [string[]]$AttachFiles = @(),
        [string]$VerdictMarker = "",
        [string[]]$AllowedVerdicts = @()
    )

    $modelList = Get-UniqueModels -Models $Models
    $attempt = 0

    foreach ($model in $modelList) {
        $attempt++
        $safeModel = Convert-ToSafeName -Value $model
        $logFile = "$LogBasePath-attempt$attempt-$safeModel.log"

        Write-Section "MODEL ATTEMPT $attempt/$($modelList.Count): $model"

        $modelVariant = $Variant
        if ($null -ne $VariantByModel -and $VariantByModel.ContainsKey($model)) {
            $modelVariant = [string]$VariantByModel[$model]
        }

        $exitCode = Invoke-OpenCode `
            -Model $model `
            -Variant $modelVariant `
            -PromptText $PromptText `
            -LogFile $logFile `
            -AttachFiles $AttachFiles

        $providerUnavailable = Test-ProviderUnavailable -LogFile $logFile

        if ($providerUnavailable) {
            Write-Warning "Provider/quota failure detected for $model. Trying next model."
            continue
        }

        if ($exitCode -ne 0) {
            Write-Warning "$model exited with code $exitCode. Trying next model."
            continue
        }

        $verdict = $null

        if (-not [string]::IsNullOrWhiteSpace($VerdictMarker)) {
            $verdict = Get-LastVerdict `
                -LogFile $logFile `
                -Marker $VerdictMarker `
                -Allowed $AllowedVerdicts

            if (-not $verdict) {
                Write-Warning "$model completed but did not emit a valid $VerdictMarker. Trying next model."
                continue
            }
        }

        return @{
            Success = $true
            ExitCode = 0
            Model = $model
            LogFile = $logFile
            Verdict = $verdict
        }
    }

    return @{
        Success = $false
        ExitCode = 1
        Model = $null
        LogFile = $null
        Verdict = $null
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

    $implChain = @($ImplementModel)

    $lines = @(
        "# Auto Quality Loop Status",
        "",
        "- State: **$State**",
        "- Cycle: **$Cycle**",
        "- Updated: **$timestamp**",
        "- Implementation chain: $($implChain -join ' -> ')",
        "- QA chain: $($qaModels -join ' -> ')",
        "- Implementation primary variant: $ImplementVariant",
        "- Implementation fallback variant: $ImplementFallbackVariant",
        "- QA primary variant: $QaVariant",
        "- QA fallback variant: $QaFallbackVariant",
        "- Review model: $ReviewModel",
        "- Review variant: $ReviewVariant",
        "",
        $Details
    )

    Set-Content -LiteralPath $statusFile -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
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
16. Because this project is Docker-first, do NOT treat host Java/Node/Maven test failures as project failures when the documented Docker test commands pass. Report host-toolchain failures only as irrelevant/environmental unless the requirements explicitly require host runtimes.

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
- Docker-first requirement: use Docker-backed verification, not host Java/Node/Maven as the source of truth

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
- Docker-first requirement: host Java/Node/Maven failures are not gate failures if Docker verification passes and host runtimes are explicitly not required

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

$implementationModels = Get-UniqueModels -Models @($ImplementModel, $ImplementFallbackModel)
$implementationVariants = @{}
if (-not [string]::IsNullOrWhiteSpace($ImplementModel)) {
    $implementationVariants[$ImplementModel] = $ImplementVariant
}
if (-not [string]::IsNullOrWhiteSpace($ImplementFallbackModel)) {
    $implementationVariants[$ImplementFallbackModel] = $ImplementFallbackVariant
}

$qaModels = Get-UniqueModels -Models @($QaModel, $QaFallbackModel)
$qaVariants = @{}
if (-not [string]::IsNullOrWhiteSpace($QaModel)) {
    $qaVariants[$QaModel] = $QaVariant
}
if (-not [string]::IsNullOrWhiteSpace($QaFallbackModel)) {
    $qaVariants[$QaFallbackModel] = $QaFallbackVariant
}

Write-Section "AUTO QUALITY LOOP V7 FALLBACK START"
Write-Host "Project root         : $ProjectRoot"
Write-Host "Implementation chain : $($implementationModels -join ' -> ')"
Write-Host "QA chain             : $($qaModels -join ' -> ')"
Write-Host "Implementation primary variant : $ImplementVariant"
Write-Host "Implementation fallback variant: $ImplementFallbackVariant"
Write-Host "QA primary variant             : $QaVariant"
Write-Host "QA fallback variant            : $QaFallbackVariant"
Write-Host "Review/final gate     : $ReviewModel"
Write-Host "Review variant        : $ReviewVariant"
Write-Host "Roles                 : CODE=$($implementationModels -join ' -> ') | QA=$($qaModels -join ' -> ') | REVIEW=$ReviewModel"
Write-Host "Max cycles            : $MaxCycles (0 = unlimited)"
Write-Host "Skip initial          : $SkipInitialImplementation"
if (-not [string]::IsNullOrWhiteSpace($ResumeFixReviewLog)) {
    Write-Host "Resume fix review log : $ResumeFixReviewLog"
}

$cycle = 1

if (-not [string]::IsNullOrWhiteSpace($ResumeFixReviewLog)) {
    $resumeReviewPath = Resolve-ProjectPath $ResumeFixReviewLog
    Ensure-Exists -PathValue $resumeReviewPath -Label "Resume review log"

    $resumeCycle = 0
    $resumeName = [System.IO.Path]::GetFileName($resumeReviewPath)
    if ($resumeName -match 'code-review-(\d+)') {
        $resumeCycle = [int]$Matches[1]
    }

    Write-Section "RESUME IMPLEMENTATION FIX FROM EXISTING REVIEW"
    Save-Status -State "RESUME_FIXING_REVIEW_FINDINGS" -Cycle $resumeCycle -Details "Review log: $resumeReviewPath"

    $resumeFixPrompt = @"
You are the IMPLEMENTATION AND FIX agent.

Resume from the CURRENT repository state and fix ALL actionable findings in the attached independent review report.

Rules:
- Work autonomously.
- Read the actual current source before editing.
- Fix root causes.
- Do not fake completion.
- Do not weaken security, validation, compiler checks, TypeScript strictness, or tests just to make them green.
- Preserve Docker-first operation.
- Run appropriate Docker build and tests after changes.
- Update docs/REVIEW_FINDINGS.md and docs/QUALITY_GATE.md truthfully.
- Do NOT mark the project final DONE.
- After this fix, a fresh independent review will run.

Continue until all actionable findings are addressed as far as technically possible.
"@

    $resumeBase = Join-Path $runDir ("resume-fix-after-review-{0:D2}" -f $resumeCycle)

    $resumeFix = Invoke-ModelChain `
        -Models $implementationModels `
        -Variant $ImplementVariant `
        -VariantByModel $implementationVariants `
        -PromptText $resumeFixPrompt `
        -LogBasePath $resumeBase `
        -AttachFiles @($requirementsPath, $resumeReviewPath)

    if (-not $resumeFix.Success) {
        Save-Status -State "BLOCKED_RESUME_FIX_ALL_MODELS" -Cycle $resumeCycle -Details "All implementation models failed/unavailable. Review log: $resumeReviewPath"
        throw "Could not resume the rejected review because all implementation models failed/unavailable."
    }

    Write-Host ""
    Write-Host "RESUME FIX MODEL: $($resumeFix.Model)"
    Write-Host "RESUME FIX LOG  : $($resumeFix.LogFile)"

    if ($resumeCycle -gt 0) {
        $cycle = $resumeCycle + 1
    }
}

if (-not $SkipInitialImplementation -and [string]::IsNullOrWhiteSpace($ResumeFixReviewLog)) {
    Write-Section "INITIAL IMPLEMENTATION"

    $masterPromptPath = Join-Path $ProjectRoot "MASTER_PROMPT_FINAL_CORRECTED.txt"
    Ensure-Exists -PathValue $masterPromptPath -Label "Master prompt"

    Save-Status -State "INITIAL_IMPLEMENTATION" -Cycle 0

    $initialPrompt = Get-Content -Raw -Encoding UTF8 -LiteralPath $masterPromptPath
    $initialBase = Join-Path $runDir "initial-implementation"

    $initial = Invoke-ModelChain `
        -Models $implementationModels `
        -Variant $ImplementVariant `
        -VariantByModel $implementationVariants `
        -PromptText $initialPrompt `
        -LogBasePath $initialBase `
        -AttachFiles @($requirementsPath)

    if (-not $initial.Success) {
        Save-Status -State "BLOCKED_INITIAL_IMPLEMENTATION" -Cycle 0 -Details "All implementation models failed/unavailable."
        throw "Initial implementation failed on all configured implementation models."
    }
}

while ($true) {
    if ($MaxCycles -gt 0 -and $cycle -gt $MaxCycles) {
        Write-Section "MAX CYCLES REACHED"
        Save-Status -State "MAX_CYCLES_REACHED" -Cycle ($cycle - 1) -Details "No final approval within $MaxCycles review cycles."
        Write-Warning "Maximum cycle count reached without final approval."
        exit 2
    }

    Write-Section "CYCLE $cycle - INDEPENDENT CODE REVIEW"
    Save-Status -State "CODE_REVIEW" -Cycle $cycle

    $reviewPrompt = Get-CodeReviewPrompt
    $reviewBase = Join-Path $reviewDir ("code-review-{0:D2}" -f $cycle)

    $review = Invoke-ModelChain `
        -Models @($ReviewModel) `
        -Variant $ReviewVariant `
        -PromptText $reviewPrompt `
        -LogBasePath $reviewBase `
        -VerdictMarker "FINAL_VERDICT" `
        -AllowedVerdicts @("APPROVE", "REJECT") `
        -AttachFiles @($requirementsPath)

    if (-not $review.Success) {
        Save-Status -State "BLOCKED_REVIEW" -Cycle $cycle -Details "Review model failed, was rate-limited, or returned no valid FINAL_VERDICT."
        throw "Independent review could not complete."
    }

    Write-Host ""
    Write-Host "REVIEW MODEL   : $($review.Model)"
    Write-Host "REVIEW VERDICT : $($review.Verdict)"
    Write-Host "REVIEW LOG     : $($review.LogFile)"

    if ($review.Verdict -eq "REJECT") {
        # Never perform a final fix that cannot be independently re-reviewed.
        if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) {
            Save-Status -State "MAX_CYCLES_REACHED_REJECTED" -Cycle $cycle -Details "Last review was REJECT. No new fix was started because it could not be followed by a fresh review within MaxCycles."
            Write-Warning "Review cycle limit reached with REJECT. Increase -MaxCycles or use 0 for unlimited."
            exit 2
        }

        Write-Section "CYCLE $cycle - IMPLEMENTATION FIX WITH AUTO-FALLBACK"
        Save-Status -State "FIXING_REVIEW_FINDINGS" -Cycle $cycle -Details "Review log: $($review.LogFile)"

        $fixPrompt = @"
You are the IMPLEMENTATION AND FIX agent.

Fix ALL actionable findings in the attached independent review report.

Rules:
- Work autonomously.
- Read the actual source before editing.
- Fix root causes.
- Do not fake completion.
- Do not weaken security, validation, compiler checks, TypeScript strictness, or tests just to make them green.
- Preserve Docker-first operation.
- Do NOT treat host Java/Node/Maven failures as project failures when Docker verification passes; the project explicitly does not require host runtimes.
- Run appropriate Docker build and tests after changes.
- Update docs/REVIEW_FINDINGS.md and docs/QUALITY_GATE.md truthfully.
- Do NOT mark the project final DONE.
- A fresh independent review will run after you finish.

Continue until all actionable findings are addressed as far as technically possible.
"@

        $fixBase = Join-Path $runDir ("fix-after-review-{0:D2}" -f $cycle)

        $fix = Invoke-ModelChain `
            -Models $implementationModels `
            -Variant $ImplementVariant `
            -VariantByModel $implementationVariants `
            -PromptText $fixPrompt `
            -LogBasePath $fixBase `
            -AttachFiles @($requirementsPath, $review.LogFile)

        if (-not $fix.Success) {
            Save-Status -State "BLOCKED_FIX_ALL_MODELS" -Cycle $cycle -Details "All implementation models are unavailable/failed. Review log: $($review.LogFile)"
            throw "All implementation models failed or are rate-limited. The script will NOT continue to another review without a real fix."
        }

        Write-Host ""
        Write-Host "FIX MODEL: $($fix.Model)"
        Write-Host "FIX LOG  : $($fix.LogFile)"

        $cycle++
        continue
    }

    Write-Section "CYCLE $cycle - LIVE QA WITH AUTO-FALLBACK"
    Save-Status -State "LIVE_QA" -Cycle $cycle

    $qaPrompt = Get-QaPrompt
    $qaBase = Join-Path $qaDir ("qa-{0:D2}" -f $cycle)

    $qa = Invoke-ModelChain `
        -Models $qaModels `
        -Variant $QaVariant `
        -VariantByModel $qaVariants `
        -PromptText $qaPrompt `
        -LogBasePath $qaBase `
        -VerdictMarker "QA_VERDICT" `
        -AllowedVerdicts @("PASS", "FAIL") `
        -AttachFiles @($requirementsPath, $review.LogFile)

    if (-not $qa.Success) {
        Save-Status -State "BLOCKED_QA_ALL_MODELS" -Cycle $cycle -Details "All QA models failed/unavailable or returned no valid QA_VERDICT."
        throw "Live QA could not complete on any configured QA model."
    }

    Write-Host ""
    Write-Host "QA MODEL   : $($qa.Model)"
    Write-Host "QA VERDICT : $($qa.Verdict)"
    Write-Host "QA LOG     : $($qa.LogFile)"

    if ($qa.Verdict -eq "FAIL") {
        if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) {
            Save-Status -State "MAX_CYCLES_REACHED_QA_FAIL" -Cycle $cycle -Details "QA failed. No unreviewable fix was started."
            Write-Warning "Cycle limit reached with QA FAIL. Increase -MaxCycles or use 0 for unlimited."
            exit 2
        }

        Write-Section "CYCLE $cycle - FIX QA FAILURES WITH AUTO-FALLBACK"
        Save-Status -State "FIXING_QA_FAILURES" -Cycle $cycle -Details "QA log: $($qa.LogFile)"

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
- A fresh independent review will run after fixes.
"@

        $fixQaBase = Join-Path $runDir ("fix-after-qa-{0:D2}" -f $cycle)

        $fixQa = Invoke-ModelChain `
            -Models $implementationModels `
            -Variant $ImplementVariant `
            -VariantByModel $implementationVariants `
            -PromptText $fixQaPrompt `
            -LogBasePath $fixQaBase `
            -AttachFiles @($requirementsPath, $qa.LogFile)

        if (-not $fixQa.Success) {
            Save-Status -State "BLOCKED_FIX_QA_ALL_MODELS" -Cycle $cycle -Details "All implementation models failed/unavailable."
            throw "Could not fix QA failures because all implementation models failed/unavailable."
        }

        $cycle++
        continue
    }

    Write-Section "CYCLE $cycle - FINAL QUALITY GATE"
    Save-Status -State "FINAL_GATE" -Cycle $cycle

    $gatePrompt = Get-FinalGatePrompt
    $gateBase = Join-Path $gateDir ("final-gate-{0:D2}" -f $cycle)

    $gate = Invoke-ModelChain `
        -Models @($ReviewModel) `
        -Variant $ReviewVariant `
        -PromptText $gatePrompt `
        -LogBasePath $gateBase `
        -VerdictMarker "FINAL_GATE" `
        -AllowedVerdicts @("APPROVE", "REJECT") `
        -AttachFiles @($requirementsPath, $review.LogFile, $qa.LogFile)

    if (-not $gate.Success) {
        Save-Status -State "BLOCKED_FINAL_GATE" -Cycle $cycle -Details "Review model final gate failed/rate-limited or returned no valid FINAL_GATE."
        throw "Final quality gate could not complete."
    }

    Write-Host ""
    Write-Host "FINAL GATE MODEL   : $($gate.Model)"
    Write-Host "FINAL GATE VERDICT : $($gate.Verdict)"
    Write-Host "FINAL GATE LOG     : $($gate.LogFile)"

    if ($gate.Verdict -eq "APPROVE") {
        Write-Section "QUALITY LOOP COMPLETE"

        $details = @(
            "Independent code review: APPROVE",
            "Review model: $($review.Model)",
            "Review log: $($review.LogFile)",
            "",
            "Live QA: PASS",
            "QA model: $($qa.Model)",
            "QA log: $($qa.LogFile)",
            "",
            "Final gate: APPROVE",
            "Final gate model: $($gate.Model)",
            "Final gate log: $($gate.LogFile)"
        ) -join [Environment]::NewLine

        Save-Status -State "APPROVED" -Cycle $cycle -Details $details

        Write-Host ""
        Write-Host "APPROVED"
        Write-Host "Status file: docs\AUTO_QUALITY_LOOP_STATUS.md"
        exit 0
    }

    if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) {
        Save-Status -State "MAX_CYCLES_REACHED_GATE_REJECT" -Cycle $cycle -Details "Final gate rejected. No unreviewable fix was started."
        Write-Warning "Cycle limit reached with final gate REJECT. Increase -MaxCycles or use 0 for unlimited."
        exit 2
    }

    Write-Section "CYCLE $cycle - FIX FINAL GATE FINDINGS WITH AUTO-FALLBACK"
    Save-Status -State "FIXING_FINAL_GATE_FINDINGS" -Cycle $cycle -Details "Final gate log: $($gate.LogFile)"

    $fixGatePrompt = @"
You are the IMPLEMENTATION AND FIX agent.

The attached independent FINAL GATE report REJECTED the current project.

Fix ALL actionable final-gate findings.

Rules:
- Inspect and fix the real source.
- Preserve Docker-first operation and security.
- Run relevant Docker build and tests.
- Do not fake evidence.
- Do NOT mark final DONE.
- After fixes, the automation will run a fresh independent code review, live QA, and independent final gate again.
"@

    $fixGateBase = Join-Path $runDir ("fix-after-final-gate-{0:D2}" -f $cycle)

    $fixGate = Invoke-ModelChain `
        -Models $implementationModels `
        -Variant $ImplementVariant `
        -VariantByModel $implementationVariants `
        -PromptText $fixGatePrompt `
        -LogBasePath $fixGateBase `
        -AttachFiles @($requirementsPath, $gate.LogFile)

    if (-not $fixGate.Success) {
        Save-Status -State "BLOCKED_FIX_GATE_ALL_MODELS" -Cycle $cycle -Details "All implementation models failed/unavailable."
        throw "Could not fix final gate findings because all implementation models failed/unavailable."
    }

    $cycle++
}
