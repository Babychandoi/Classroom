param([string]$Address = '192.168.1.7', [switch]$SkipBuild, [switch]$SingleBackend, [switch]$TwoBackends)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $repo
try {
    if ($Address -notmatch '^[a-zA-Z0-9.-]+$') { throw 'Address must be an IP or hostname without scheme/path.' }
    if ($SingleBackend -and $TwoBackends) { throw 'Choose one backend topology.' }
    $env:SERVER_ADDRESS = $Address
    $composeArguments = @('compose','--env-file','infra/.env','-f','infra/compose.yaml','-f','infra/compose.server.yaml')
    # One JVM is the default on this PC; two nodes remain available for distributed checks.
    if ($TwoBackends) { $composeArguments += @('-f','infra/compose.multinode.yaml','-f','infra/compose.multinode-server.yaml') }
    & docker @composeArguments config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Invalid server configuration.' }
    # Caddy must exist in Docker DNS before nginx resolves its trusted-proxy name.
    & docker @composeArguments up -d caddy
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start HTTPS ingress.' }
    $backendServices = @('backend')
    if ($TwoBackends) { $backendServices += 'backend-second' }
    # Build once. Building again through frontend's dependencies can produce a new
    # image manifest and restart a backend that has just become ready.
    if (-not $SkipBuild) {
        & docker @composeArguments build @backendServices frontend
        if ($LASTEXITCODE -ne 0) { throw 'Server image build failed.' }
    }
    $upArguments = @('up','-d','--wait','--wait-timeout','300','--no-build')
    & docker @composeArguments @upArguments @backendServices
    if ($LASTEXITCODE -ne 0) { throw 'Backend readiness failed.' }
    & docker @composeArguments @upArguments --no-deps frontend
    if ($LASTEXITCODE -ne 0) { throw 'Server readiness failed.' }
    if (-not $TwoBackends) {
        # Drain routing first, then stop only this project's optional second backend.
        $secondary = @(& docker ps -q --filter 'label=com.docker.compose.project=online-classroom' --filter 'label=com.docker.compose.service=backend-second')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot check optional backend state.' }
        if ($secondary.Count -gt 0) {
            & docker stop @secondary
            if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the unused optional backend.' }
        }
    }
    $certDir = Join-Path $repo '.artifacts/server'
    New-Item -ItemType Directory -Force $certDir | Out-Null
    & docker cp 'classroom-caddy:/data/caddy/pki/authorities/local/root.crt' (Join-Path $certDir 'classroom-root.crt')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot export the public local CA certificate.' }
    Write-Host "HTTPS server started: https://$Address and https://localhost"
    Write-Host 'For trusted LAN HTTPS, import .artifacts/server/classroom-root.crt into Trusted Root Certification Authorities on each client.'
} finally { Pop-Location }
