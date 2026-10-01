param([string]$Address = '192.168.1.7')
$ErrorActionPreference = 'Continue'
$deadline = (Get-Date).AddMinutes(5)
do {
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -eq 0) {
        & (Join-Path $PSScriptRoot 'start-server.ps1') -Address $Address -SkipBuild
        exit $LASTEXITCODE
    }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
Write-Error 'Docker Desktop did not start within five minutes. Start Docker Desktop and start-server.ps1.'
exit 1
