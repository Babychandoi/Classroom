param([string]$Address = '192.168.1.7')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logDir = Join-Path $repo '.artifacts/server'
New-Item -ItemType Directory -Force $logDir | Out-Null
$status = & curl.exe --silent --show-error --max-time 10 --ssl-no-revoke --cacert (Join-Path $logDir 'classroom-root.crt') "https://$Address/api/v1/health/readiness"
$successful = $LASTEXITCODE -eq 0
if ($successful) { try { $successful = ($status | ConvertFrom-Json).status -eq 'UP' } catch { $successful = $false } }
$entry = @{ time=(Get-Date).ToUniversalTime().ToString('o'); address=$Address; ready=$successful } | ConvertTo-Json -Compress
Add-Content -LiteralPath (Join-Path $logDir 'health.jsonl') -Value $entry
if (-not $successful) { Write-Error 'Classroom HTTPS readiness failed. See Docker health/logs.'; exit 1 }
Write-Host 'HTTPS and MySQL readiness passed.'
