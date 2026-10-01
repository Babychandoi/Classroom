param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $hadHttps = (& docker ps --filter 'name=^classroom-caddy$' --format '{{.Names}}') -eq 'classroom-caddy'
    $live = @('compose','--env-file','infra/.env','-f','infra/compose.yaml')
    $demo = @('compose','-p','classroom-demo','--env-file','infra/.env','-f','infra/compose.yaml','-f','infra/compose.demo.yaml','-f','infra/compose.drill.yaml')
    $previousPrefix = $env:DRILL_PREFIX
    $env:DRILL_PREFIX = 'classroom-demo'
    try {
        foreach ($stack in @(@{ Arguments=$live }, @{ Arguments=$demo })) {
            $composeArguments = $stack.Arguments
            & docker @composeArguments config --quiet
            if ($LASTEXITCODE -ne 0) { throw 'Invalid Compose configuration.' }
            $upArguments = @('up','-d','--force-recreate','--wait','--wait-timeout','300')
            if (-not $SkipBuild) { $upArguments += '--build' }
            & docker @composeArguments @upArguments mysql mongodb neo4j minio minio-init backend frontend
            if ($LASTEXITCODE -ne 0) { throw 'Stack recreation or readiness failed. Existing volumes are preserved.' }
        }
    } finally { $env:DRILL_PREFIX = $previousPrefix }
    foreach ($port in @(3000,13000)) {
        $health = Invoke-RestMethod "http://localhost:$port/api/v1/health/readiness"
        Write-Host "Verified frontend proxy and backend readiness on port $port"
    }
    if ($hadHttps) { & (Join-Path $PSScriptRoot 'infra/scripts/start-server.ps1') -SkipBuild }
    Write-Host 'Both stacks recreated successfully. No volumes were deleted.'
} finally { Pop-Location }
