param(
    [string]$ProjectRoot = "D:\ClassRoom",
    [string]$RequirementsFile = "Bo-tai-lieu-he-thong-lop-hoc-v0.1.md",

    # Chain syntax:
    #   claude|default|
    #   opencode|google/antigravity-gemini-3.8-flash|
    #   opencode|openai/gpt-6-luna|medium
    # Multiple agents are separated by semicolons and tried left-to-right.
    [string]$ImplementChain = "claude|default|;opencode|openai/gpt-6-luna|medium",
    [string]$QaChain = "claude|default|;opencode|openai/gpt-6-luna|medium",
    [string]$ReviewChain = "opencode|google/antigravity-gemini-3.8-flash|;opencode|openai/gpt-6-sol|medium",

    # FINAL GATE is intentionally GPT-6 Sol only.
    # If Sol is unavailable/quota-limited, the loop stops instead of accepting another model.
    [string]$FinalGateChain = "opencode|openai/gpt-6-sol|medium",

    # Claude Code CLI permission mode. "acceptEdits" auto-accepts file edits.
    # Shell commands still follow your Claude Code permission/settings rules.
    [string]$ClaudePermissionMode = "acceptEdits",
    [switch]$ClaudeBypassPermissions,

    # 0 = unlimited review/fix cycles.
    [int]$MaxCycles = 0
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
    param([string]$PathValue, [string]$Label)
    if (-not (Test-Path -LiteralPath $PathValue)) {
        throw "$Label not found: $PathValue"
    }
}

function Resolve-OpenCodeCommand {
    $native = Get-Command opencode.exe -ErrorAction SilentlyContinue
    if ($native) { return $native.Source }

    $psShim = Get-Command opencode.ps1 -ErrorAction SilentlyContinue
    if ($psShim) {
        $shimDir = Split-Path -Parent $psShim.Source
        $candidate = Join-Path $shimDir "node_modules\opencode-ai\bin\opencode.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
        return $psShim.Source
    }

    $cmdShim = Get-Command opencode.cmd -ErrorAction SilentlyContinue
    if ($cmdShim) { return $cmdShim.Source }

    return $null
}

function Resolve-ClaudeCommand {
    foreach ($name in @("claude.exe", "claude.cmd", "claude.ps1", "claude")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

function Convert-ToSafeName {
    param([string]$Value)
    $safe = $Value -replace '[^A-Za-z0-9._-]', '-'
    $safe = $safe -replace '-+', '-'
    return $safe.Trim('-')
}

function Parse-AgentChain {
    param([string]$Chain)

    $agents = @()
    if ([string]::IsNullOrWhiteSpace($Chain)) { return $agents }

    foreach ($entry in ($Chain -split ';')) {
        $trimmed = $entry.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }

        $parts = $trimmed -split '\|', 3
        if ($parts.Count -lt 2) {
            throw "Invalid agent spec '$trimmed'. Expected provider|model|variant"
        }

        $provider = $parts[0].Trim().ToLowerInvariant()
        $model = $parts[1].Trim()
        $variant = ""
        if ($parts.Count -ge 3) { $variant = $parts[2].Trim() }

        if ($provider -notin @("opencode", "claude")) {
            throw "Unsupported provider '$provider' in '$trimmed'."
        }

        if ([string]::IsNullOrWhiteSpace($model)) {
            throw "Model cannot be empty in '$trimmed'."
        }

        $agents += [PSCustomObject]@{
            Provider = $provider
            Model = $model
            Variant = $variant
            Raw = $trimmed
        }
    }

    return $agents
}

function Test-ProviderUnavailable {
    param([string]$LogFile)

    if (-not (Test-Path -LiteralPath $LogFile)) { return $false }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $LogFile
    $patterns = @(
        'All\s+\d+\s+account\(s\)\s+rate-limited',
        'rate[- ]limited',
        'quota\s+resets',
        'quota\s+(exceeded|exhausted|reached)',
        'usage\s+limit\s+(has\s+been\s+)?reached',
        'you.?ve\s+hit\s+your\s+limit',
        'limit\s+reached',
        'too\s+many\s+requests',
        'no\s+available\s+account',
        'provider\s+(is\s+)?unavailable',
        'model\s+(is\s+)?unavailable',
        'service\s+unavailable',
        '\bHTTP\s*429\b',
        '\b429\b.*(quota|rate|limit)',
        'resource\s+exhausted'
    )

    foreach ($pattern in $patterns) {
        if ($content -match "(?i)$pattern") { return $true }
    }

    return $false
}

function Get-LastVerdict {
    param(
        [string]$LogFile,
        [string]$Marker,
        [string[]]$Allowed
    )

    if (-not (Test-Path -LiteralPath $LogFile)) { return $null }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $LogFile
    $escapedMarker = [Regex]::Escape($Marker)
    $allowedPattern = ($Allowed | ForEach-Object { [Regex]::Escape($_) }) -join "|"
    $pattern = "(?im)^\s*" + $escapedMarker + "\s*:\s*(" + $allowedPattern + ")\s*$"
    $matches = [Regex]::Matches($content, $pattern)

    if ($matches.Count -eq 0) { return $null }
    return $matches[$matches.Count - 1].Groups[1].Value.ToUpperInvariant()
}

function Invoke-OpenCodeAgent {
    param(
        [string]$Model,
        [string]$Variant,
        [string]$PromptText,
        [string]$LogFile,
        [string[]]$ContextFiles
    )

    $command = Resolve-OpenCodeCommand
    if (-not $command) {
        Set-Content -LiteralPath $LogFile -Value "OpenCode command not found." -Encoding UTF8
        return 127
    }

    $args = @("run", "--auto", "-m", $Model)
    if (-not [string]::IsNullOrWhiteSpace($Variant)) {
        $args += @("--variant", $Variant)
    }

    # Prompt MUST appear before --file because --file is array-valued.
    $args += $PromptText

    foreach ($file in $ContextFiles) {
        if (-not [string]::IsNullOrWhiteSpace($file) -and (Test-Path -LiteralPath $file)) {
            $args += @("--file", $file)
        }
    }

    if (Test-Path -LiteralPath $LogFile) {
        Remove-Item -LiteralPath $LogFile -Force
    }

    Write-Host "ENGINE  : OpenCode"
    Write-Host "COMMAND : $command"
    Write-Host "MODEL   : $Model"
    if ($Variant) { Write-Host "VARIANT : $Variant" }
    Write-Host "LOG     : $LogFile"
    Write-Host ""

    $global:LASTEXITCODE = 0
    Push-Location $ProjectRoot
    try {
        & $command @args 2>&1 |
            ForEach-Object {
                $line = $_.ToString()
                Write-Host $line
                Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
            }
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    if ($null -eq $exitCode) { $exitCode = 1 }
    return [int]$exitCode
}

function Invoke-ClaudeAgent {
    param(
        [string]$Model,
        [string]$PromptText,
        [string]$LogFile,
        [string[]]$ContextFiles
    )

    $command = Resolve-ClaudeCommand
    if (-not $command) {
        Set-Content -LiteralPath $LogFile -Value "Claude Code CLI command not found." -Encoding UTF8
        return 127
    }

    $contextBlock = ""
    if ($ContextFiles.Count -gt 0) {
        $existing = @($ContextFiles | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
        if ($existing.Count -gt 0) {
            $contextBlock = @"

CONTEXT FILES:
Read these files directly from disk before acting:
$($existing | ForEach-Object { "- $_" } | Out-String)

"@
        }
    }

    $fullPrompt = $PromptText + $contextBlock

    $args = @("-p", $fullPrompt, "--no-session-persistence")
    if (-not [string]::IsNullOrWhiteSpace($Model) -and $Model -ne "default") {
        $args += @("--model", $Model)
    }

    if ($ClaudeBypassPermissions) {
        $args += "--dangerously-skip-permissions"
    }
    elseif (-not [string]::IsNullOrWhiteSpace($ClaudePermissionMode)) {
        $args += @("--permission-mode", $ClaudePermissionMode)
    }

    if (Test-Path -LiteralPath $LogFile) {
        Remove-Item -LiteralPath $LogFile -Force
    }

    Write-Host "ENGINE  : Claude Code CLI"
    Write-Host "COMMAND : $command"
    Write-Host "MODEL   : $Model"
    Write-Host "PERM    : $(if ($ClaudeBypassPermissions) {'bypassPermissions'} else {$ClaudePermissionMode})"
    Write-Host "LOG     : $LogFile"
    Write-Host ""

    $global:LASTEXITCODE = 0
    Push-Location $ProjectRoot
    try {
        & $command @args 2>&1 |
            ForEach-Object {
                $line = $_.ToString()
                Write-Host $line
                Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
            }
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    if ($null -eq $exitCode) { $exitCode = 1 }
    return [int]$exitCode
}

function Invoke-AgentChain {
    param(
        [Parameter(Mandatory=$true)][object[]]$Agents,
        [Parameter(Mandatory=$true)][string]$PromptText,
        [Parameter(Mandatory=$true)][string]$LogBasePath,
        [string[]]$ContextFiles = @(),
        [string]$VerdictMarker = "",
        [string[]]$AllowedVerdicts = @()
    )

    $attempt = 0

    foreach ($agent in $Agents) {
        $attempt++
        $safe = Convert-ToSafeName "$($agent.Provider)-$($agent.Model)-$($agent.Variant)"
        $logFile = "$LogBasePath-attempt$attempt-$safe.log"

        Write-Section "AGENT ATTEMPT $attempt/$($Agents.Count): $($agent.Raw)"

        if ($agent.Provider -eq "claude") {
            $exitCode = Invoke-ClaudeAgent `
                -Model $agent.Model `
                -PromptText $PromptText `
                -LogFile $logFile `
                -ContextFiles $ContextFiles
        }
        else {
            $exitCode = Invoke-OpenCodeAgent `
                -Model $agent.Model `
                -Variant $agent.Variant `
                -PromptText $PromptText `
                -LogFile $logFile `
                -ContextFiles $ContextFiles
        }

        if (Test-ProviderUnavailable -LogFile $logFile) {
            Write-Warning "Quota/provider failure detected for $($agent.Raw). Trying next agent."
            continue
        }

        if ($exitCode -ne 0) {
            Write-Warning "$($agent.Raw) exited with code $exitCode. Trying next agent."
            continue
        }

        $verdict = $null
        if ($VerdictMarker) {
            $verdict = Get-LastVerdict -LogFile $logFile -Marker $VerdictMarker -Allowed $AllowedVerdicts
            if (-not $verdict) {
                Write-Warning "$($agent.Raw) did not emit valid $VerdictMarker. Trying next agent."
                continue
            }
        }

        return @{
            Success = $true
            Agent = $agent.Raw
            Provider = $agent.Provider
            Model = $agent.Model
            LogFile = $logFile
            Verdict = $verdict
        }
    }

    return @{
        Success = $false
        Agent = $null
        Provider = $null
        Model = $null
        LogFile = $null
        Verdict = $null
    }
}

function Chain-ToText {
    param([object[]]$Agents)
    return (($Agents | ForEach-Object { $_.Raw }) -join " -> ")
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
        "- CODE/FIX chain: $(Chain-ToText $implementationAgents)",
        "- QA chain: $(Chain-ToText $qaAgents)",
        "- REVIEW chain: $(Chain-ToText $reviewAgents)",
        "- FINAL GATE chain: $(Chain-ToText $finalGateAgents)",
        "- Claude permission mode: $ClaudePermissionMode",
        "",
        $Details
    )

    $statusDir = Split-Path -Parent $statusFile
    if (-not (Test-Path -LiteralPath $statusDir)) {
        New-Item -ItemType Directory -Path $statusDir -Force | Out-Null
    }

    Set-Content -LiteralPath $statusFile -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
}

function Get-CodeReviewPrompt {
    return @"
You are an INDEPENDENT SENIOR SOFTWARE CODE REVIEWER.

ROLE SEPARATION:
- You are NOT the implementation agent.
- Do NOT edit application source code.
- Inspect the CURRENT repository state yourself.
- Do not trust README/status/review docs as proof.

Review the supplied requirements against the real repository.

MANDATORY:
1. Audit backend, frontend, security/RBAC, business rules, persistence, migrations, concurrency/idempotency, commerce/payment, exams, media, segments/ranking, projections/outbox, Docker/config, and tests.
2. This is Docker-first. Host Java/Node/Maven absence is irrelevant when Docker verification works.
3. Run appropriate Docker verification where practical.
4. Findings must be concrete, evidence-based, and actionable.
5. Do not invent requirements.
6. Passing tests are evidence, not proof that no defects remain.
7. Do NOT edit the project.

Return findings with severity, evidence, impact, and required fix.

End with EXACTLY one line:
FINAL_VERDICT: APPROVE
or
FINAL_VERDICT: REJECT
"@
}

function Get-QaPrompt {
    return @"
You are the LIVE QA / SYSTEM VERIFICATION agent.

Use the CURRENT repository and the supplied requirements.

MANDATORY:
- Work Docker-first.
- Exercise representative real application flows, not only static inspection.
- Run backend/frontend tests and build checks where appropriate.
- Verify services/configuration/health.
- Check key auth, permissions, exam, commerce, media and persistence flows.
- Do not weaken tests or modify application behavior merely to force PASS.
- If permissions prevent required verification, report FAIL rather than pretending it ran.

You MAY fix only trivial test-harness/environment issues that do not alter product behavior.
Do not make broad product changes in QA.

End with EXACTLY one line:
QA_VERDICT: PASS
or
QA_VERDICT: FAIL
"@
}

function Get-FinalGatePrompt {
    return @"
You are the FINAL INDEPENDENT RELEASE GATE.

Do NOT edit source code.

Perform a fresh high-confidence check of the CURRENT repository against the supplied requirements.
Use the attached latest review and QA reports only as context, not as proof.

Check:
- unresolved security/RBAC defects
- business-rule correctness
- concurrency/idempotency/data integrity
- Docker-first runtime/configuration
- migrations and persistence
- exam, commerce, media, ranking/segment and projection flows
- regression risk introduced by recent fixes
- whether verification evidence is sufficient

Only approve when there are no material unresolved findings.

End with EXACTLY one line:
FINAL_GATE: APPROVE
or
FINAL_GATE: REJECT
"@
}

$requirementsPath = Resolve-ProjectPath $RequirementsFile
Ensure-Exists -PathValue $ProjectRoot -Label "Project root"
Ensure-Exists -PathValue $requirementsPath -Label "Requirements file"

$implementationAgents = @(Parse-AgentChain $ImplementChain)
$qaAgents = @(Parse-AgentChain $QaChain)
$reviewAgents = @(Parse-AgentChain $ReviewChain)
$finalGateAgents = @(Parse-AgentChain $FinalGateChain)

if ($implementationAgents.Count -eq 0) { throw "ImplementChain is empty." }
if ($qaAgents.Count -eq 0) { throw "QaChain is empty." }
if ($reviewAgents.Count -eq 0) { throw "ReviewChain is empty." }
if ($finalGateAgents.Count -eq 0) { throw "FinalGateChain is empty." }

$automationDir = Join-Path $ProjectRoot "automation"
$runDir = Join-Path $automationDir "runs"
$reviewDir = Join-Path $automationDir "reviews"
$qaDir = Join-Path $automationDir "qa"
$gateDir = Join-Path $automationDir "final-gates"

foreach ($dir in @($automationDir, $runDir, $reviewDir, $qaDir, $gateDir)) {
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}

Write-Section "AUTO QUALITY LOOP V8.2 - ALL3 / GPT-6 SOL FINAL GATE"
Write-Host "PROJECT      : $ProjectRoot"
Write-Host "CODE/FIX     : $(Chain-ToText $implementationAgents)"
Write-Host "QA           : $(Chain-ToText $qaAgents)"
Write-Host "REVIEW       : $(Chain-ToText $reviewAgents)"
Write-Host "FINAL GATE   : $(Chain-ToText $finalGateAgents)"
Write-Host "MAX CYCLES   : $MaxCycles (0 = unlimited)"
Write-Host ""

$cycle = 1

while ($true) {
    if ($MaxCycles -gt 0 -and $cycle -gt $MaxCycles) {
        Write-Section "MAX CYCLES REACHED"
        Save-Status -State "MAX_CYCLES_REACHED" -Cycle ($cycle - 1)
        exit 2
    }

    # -------------------------------------------------------------------------
    # 1. INDEPENDENT REVIEW
    # -------------------------------------------------------------------------
    Write-Section "CYCLE $cycle - INDEPENDENT CODE REVIEW"
    Save-Status -State "CODE_REVIEW" -Cycle $cycle

    $review = Invoke-AgentChain `
        -Agents $reviewAgents `
        -PromptText (Get-CodeReviewPrompt) `
        -LogBasePath (Join-Path $reviewDir ("code-review-{0:D2}" -f $cycle)) `
        -ContextFiles @($requirementsPath) `
        -VerdictMarker "FINAL_VERDICT" `
        -AllowedVerdicts @("APPROVE", "REJECT")

    if (-not $review.Success) {
        Save-Status -State "BLOCKED_REVIEW" -Cycle $cycle -Details "All review agents failed/unavailable or emitted no valid verdict."
        throw "Independent review could not complete."
    }

    Write-Host ""
    Write-Host "REVIEW AGENT   : $($review.Agent)"
    Write-Host "REVIEW VERDICT : $($review.Verdict)"
    Write-Host "REVIEW LOG     : $($review.LogFile)"

    if ($review.Verdict -eq "REJECT") {
        if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) {
            Save-Status -State "MAX_CYCLES_REACHED_REJECTED" -Cycle $cycle -Details "Last review was REJECT."
            exit 2
        }

        # ---------------------------------------------------------------------
        # 2. IMPLEMENTATION FIX
        # ---------------------------------------------------------------------
        Write-Section "CYCLE $cycle - IMPLEMENTATION FIX"
        Save-Status -State "FIXING_REVIEW_FINDINGS" -Cycle $cycle -Details "Review log: $($review.LogFile)"

        $fixPrompt = @"
You are the IMPLEMENTATION / FIX agent.

Fix ALL actionable findings in the attached independent review report against the CURRENT repository.

MANDATORY:
- Read current source before editing.
- Fix root causes, not symptoms.
- Preserve security, validation, TypeScript strictness, compiler checks and tests.
- Preserve Docker-first operation.
- Do not use destructive Git commands, force-push, delete unrelated work, or bypass project safety checks.
- Run appropriate Docker build/tests after changes.
- Update docs/REVIEW_FINDINGS.md and docs/QUALITY_GATE.md truthfully if those files exist.
- Do NOT claim final release approval.
- If you cannot perform required verification because of tool permissions, do not pretend success.

At the end, only emit SUCCESS if all actionable findings were addressed and required verification you could reasonably perform passed.

End with EXACTLY one line:
IMPLEMENTATION_RESULT: SUCCESS
or
IMPLEMENTATION_RESULT: PARTIAL
"@

        $fix = Invoke-AgentChain `
            -Agents $implementationAgents `
            -PromptText $fixPrompt `
            -LogBasePath (Join-Path $runDir ("fix-after-review-{0:D2}" -f $cycle)) `
            -ContextFiles @($requirementsPath, $review.LogFile) `
            -VerdictMarker "IMPLEMENTATION_RESULT" `
            -AllowedVerdicts @("SUCCESS", "PARTIAL")

        if (-not $fix.Success) {
            Save-Status -State "BLOCKED_FIX_ALL_AGENTS" -Cycle $cycle -Details "All implementation agents failed/unavailable/partial. Review log: $($review.LogFile)"
            throw "No implementation agent completed the required fix successfully."
        }

        Write-Host ""
        Write-Host "FIX AGENT : $($fix.Agent)"
        Write-Host "FIX LOG   : $($fix.LogFile)"

        $cycle++
        continue
    }

    # -------------------------------------------------------------------------
    # 3. LIVE QA
    # -------------------------------------------------------------------------
    Write-Section "CYCLE $cycle - LIVE QA"
    Save-Status -State "LIVE_QA" -Cycle $cycle

    $qa = Invoke-AgentChain `
        -Agents $qaAgents `
        -PromptText (Get-QaPrompt) `
        -LogBasePath (Join-Path $qaDir ("qa-{0:D2}" -f $cycle)) `
        -ContextFiles @($requirementsPath, $review.LogFile) `
        -VerdictMarker "QA_VERDICT" `
        -AllowedVerdicts @("PASS", "FAIL")

    if (-not $qa.Success) {
        Save-Status -State "BLOCKED_QA" -Cycle $cycle
        throw "Live QA could not complete."
    }

    Write-Host ""
    Write-Host "QA AGENT   : $($qa.Agent)"
    Write-Host "QA VERDICT : $($qa.Verdict)"
    Write-Host "QA LOG     : $($qa.LogFile)"

    if ($qa.Verdict -eq "FAIL") {
        if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) {
            Save-Status -State "MAX_CYCLES_REACHED_QA_FAIL" -Cycle $cycle
            exit 2
        }

        Write-Section "CYCLE $cycle - FIX QA FAILURES"
        Save-Status -State "FIXING_QA_FAILURES" -Cycle $cycle -Details "QA log: $($qa.LogFile)"

        $fixQaPrompt = @"
You are the IMPLEMENTATION / FIX agent.

The attached QA report failed. Fix every actionable QA failure in the CURRENT repository.

MANDATORY:
- Reproduce failures where practical.
- Fix root causes.
- Preserve security and Docker-first behavior.
- Run relevant Docker verification.
- Do not fake PASS.
- Do not use destructive Git commands or delete unrelated work.

End with EXACTLY one line:
IMPLEMENTATION_RESULT: SUCCESS
or
IMPLEMENTATION_RESULT: PARTIAL
"@

        $fixQa = Invoke-AgentChain `
            -Agents $implementationAgents `
            -PromptText $fixQaPrompt `
            -LogBasePath (Join-Path $runDir ("fix-after-qa-{0:D2}" -f $cycle)) `
            -ContextFiles @($requirementsPath, $qa.LogFile) `
            -VerdictMarker "IMPLEMENTATION_RESULT" `
            -AllowedVerdicts @("SUCCESS", "PARTIAL")

        if (-not $fixQa.Success) {
            Save-Status -State "BLOCKED_FIX_QA" -Cycle $cycle
            throw "QA failures could not be fixed by any implementation agent."
        }

        $cycle++
        continue
    }

    # -------------------------------------------------------------------------
    # 4. FINAL INDEPENDENT GATE
    # -------------------------------------------------------------------------
    Write-Section "CYCLE $cycle - FINAL QUALITY GATE"
    Save-Status -State "FINAL_GATE" -Cycle $cycle

    $gate = Invoke-AgentChain `
        -Agents $finalGateAgents `
        -PromptText (Get-FinalGatePrompt) `
        -LogBasePath (Join-Path $gateDir ("final-gate-{0:D2}" -f $cycle)) `
        -ContextFiles @($requirementsPath, $review.LogFile, $qa.LogFile) `
        -VerdictMarker "FINAL_GATE" `
        -AllowedVerdicts @("APPROVE", "REJECT")

    if (-not $gate.Success) {
        Save-Status -State "BLOCKED_FINAL_GATE" -Cycle $cycle
        throw "Final quality gate could not complete."
    }

    Write-Host ""
    Write-Host "FINAL GATE AGENT   : $($gate.Agent)"
    Write-Host "FINAL GATE VERDICT : $($gate.Verdict)"
    Write-Host "FINAL GATE LOG     : $($gate.LogFile)"

    if ($gate.Verdict -eq "APPROVE") {
        Write-Section "QUALITY LOOP COMPLETE"

        $details = @(
            "Review: APPROVE",
            "Review agent: $($review.Agent)",
            "Review log: $($review.LogFile)",
            "",
            "QA: PASS",
            "QA agent: $($qa.Agent)",
            "QA log: $($qa.LogFile)",
            "",
            "Final gate: APPROVE",
            "Final gate agent: $($gate.Agent)",
            "Final gate log: $($gate.LogFile)"
        ) -join [Environment]::NewLine

        Save-Status -State "APPROVED" -Cycle $cycle -Details $details
        Write-Host "APPROVED"
        exit 0
    }

    if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) {
        Save-Status -State "MAX_CYCLES_REACHED_GATE_REJECT" -Cycle $cycle
        exit 2
    }

    # -------------------------------------------------------------------------
    # 5. FIX FINAL GATE FINDINGS
    # -------------------------------------------------------------------------
    Write-Section "CYCLE $cycle - FIX FINAL GATE FINDINGS"
    Save-Status -State "FIXING_FINAL_GATE_FINDINGS" -Cycle $cycle -Details "Final gate log: $($gate.LogFile)"

    $fixGatePrompt = @"
You are the IMPLEMENTATION / FIX agent.

The attached independent FINAL GATE rejected the CURRENT repository.
Fix ALL actionable final-gate findings.

MANDATORY:
- Fix the real source and root causes.
- Preserve Docker-first operation and security.
- Run relevant Docker build/tests.
- Do not fake evidence.
- Do not use destructive Git commands or delete unrelated work.

End with EXACTLY one line:
IMPLEMENTATION_RESULT: SUCCESS
or
IMPLEMENTATION_RESULT: PARTIAL
"@

    $fixGate = Invoke-AgentChain `
        -Agents $implementationAgents `
        -PromptText $fixGatePrompt `
        -LogBasePath (Join-Path $runDir ("fix-after-final-gate-{0:D2}" -f $cycle)) `
        -ContextFiles @($requirementsPath, $gate.LogFile) `
        -VerdictMarker "IMPLEMENTATION_RESULT" `
        -AllowedVerdicts @("SUCCESS", "PARTIAL")

    if (-not $fixGate.Success) {
        Save-Status -State "BLOCKED_FIX_GATE" -Cycle $cycle
        throw "Final-gate findings could not be fixed by any implementation agent."
    }

    $cycle++
}
