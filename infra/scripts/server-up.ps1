<#
.SYNOPSIS
  Shortcuts around `docker compose` for a stack installed by install.ps1 / install.sh.

.DESCRIPTION
  Reads infra\.server.conf (PROJECT, DEMO, OVERLAYS) so the same project and compose files are
  always used. Windows PowerShell 5.1 compatible, pure ASCII.

    start     docker compose up -d            (no rebuild)
    stop      docker compose stop             (containers and volumes are kept)
    restart   docker compose restart
    status    docker compose ps + readiness probe
    logs      docker compose logs --tail 200 -f [service]
    backup    backup.ps1 incl. Neo4j dump -> <install dir>\backups\<timestamp>
    update    rebuild backend + frontend from the current source and restart them (data untouched)
    compose   any other docker compose subcommand with the right project/files

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\server-up.ps1 status
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\server-up.ps1 logs backend
#>
param(
    [Parameter(Position = 0)][string]$Command = "status",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
)

$ErrorActionPreference = "Stop"
$InfraDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).ProviderPath
$confFile = Join-Path $InfraDir ".server.conf"
$projectName = "classroom-demo"
$demo = "1"
$overlays = @()
if (Test-Path -LiteralPath $confFile) {
    foreach ($line in (Get-Content -LiteralPath $confFile)) {
        if ($line -match '^PROJECT=(.*)$') { $projectName = $Matches[1].Trim() }
        elseif ($line -match '^DEMO=(.*)$') { $demo = $Matches[1].Trim() }
        elseif ($line -match '^OVERLAYS=(.*)$') { $overlays = @($Matches[1].Trim() -split ' ' | Where-Object { $_ }) }
    }
}
$files = @("-f", "compose.yaml")
$backupOverlays = @()
if ($demo -eq "1") { $files += @("-f", "compose.demo.yaml"); $backupOverlays += @("-OverlayFile", (Join-Path $InfraDir "compose.demo.yaml")) }
foreach ($o in $overlays) { $files += @("-f", $o); $backupOverlays += @("-OverlayFile", $o) }

function Invoke-Dc([string[]]$ComposeArgs) {
    Push-Location $InfraDir
    try {
        & docker (@("compose", "-p", $projectName) + $files + @("--env-file", ".env") + $ComposeArgs) | Out-Host
        return $LASTEXITCODE
    } finally { Pop-Location }
}

$rc = 0
switch ($Command.ToLowerInvariant()) {
    "start"   { $rc = Invoke-Dc @("up", "-d") }
    "stop"    { $rc = Invoke-Dc @("stop") }
    "restart" { $rc = Invoke-Dc (@("restart") + @($Rest)) }
    "status"  {
        $rc = Invoke-Dc @("ps")
        $port = "8080"
        $envFile = Join-Path $InfraDir ".env"
        if (Test-Path -LiteralPath $envFile) {
            foreach ($line in (Get-Content -LiteralPath $envFile)) { if ($line -match '^BACKEND_PORT=(\d+)') { $port = $Matches[1] } }
        }
        try { Write-Host ("readiness: " + (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$port/api/v1/health/readiness").Content) }
        catch { Write-Host "readiness: KHONG TRA LOI" }
    }
    "logs"    { $rc = Invoke-Dc (@("logs", "--tail", "200", "-f") + @($Rest)) }
    "backup"  {
        $out = Join-Path (Split-Path -Parent $InfraDir) "backups"
        $all = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $PSScriptRoot "backup.ps1"), "-OutDir", $out, "-ProjectName", $projectName, "-InfraDir", $InfraDir, "-IncludeNeo4jDump") + $backupOverlays
        & powershell.exe @all | Out-Host
        $rc = $LASTEXITCODE
    }
    "update"  { $rc = Invoke-Dc @("up", "-d", "--build", "backend", "frontend") }
    "compose" { $rc = Invoke-Dc @($Rest) }
    default   { [Console]::Error.WriteLine("Lenh khong hop le: $Command (start|stop|restart|status|logs|backup|update|compose)"); $rc = 2 }
}
exit $rc
