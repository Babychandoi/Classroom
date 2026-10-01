param([switch]$Reapply)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$file = Join-Path $repo '.artifacts/server/accounts.json'
if ($Reapply) {
    if (-not (Test-Path -LiteralPath $file)) { throw 'Protected credentials file is required to reapply after restore.' }
    & python (Join-Path $PSScriptRoot 'secure-server-accounts.py') $file --reapply
    if ($LASTEXITCODE -ne 0) { throw 'Could not reapply protected account credentials.' }
    exit 0
}
if (Test-Path -LiteralPath $file) { throw 'Protected server credentials already exist. Read that file instead of rotating again.' }
New-Item -ItemType File -Path $file | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $file /inheritance:r /grant:r "${identity}:(F)" '*S-1-5-18:(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the credentials file.' }
& python (Join-Path $PSScriptRoot 'secure-server-accounts.py') $file
if ($LASTEXITCODE -ne 0) { throw 'Account rotation failed; preserve the recovery file.' }
