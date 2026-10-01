# Run once from an Administrator PowerShell on the server.
$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script in PowerShell as Administrator.' }
$ruleName = 'Classroom-LAN-HTTPS'
if (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue) { Remove-NetFirewallRule -Name $ruleName }
# Works with the current Public Wi-Fi profile while allowing only the local subnet.
New-NetFirewallRule -Name $ruleName -DisplayName 'Classroom HTTPS from local subnet' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80,443,9443 -RemoteAddress LocalSubnet -Profile Any | Out-Null
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
& certutil.exe -f -addstore Root (Join-Path $repo '.artifacts/server/classroom-root.crt')
if ($LASTEXITCODE -ne 0) { throw 'Could not trust the public Classroom CA certificate.' }
Write-Host 'LAN firewall configured. On other devices, install the exported public CA certificate before using HTTPS.'
