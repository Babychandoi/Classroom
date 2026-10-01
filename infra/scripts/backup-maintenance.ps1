param([int]$RetentionDays = 30)
$ErrorActionPreference = 'Stop'
if ($RetentionDays -lt 7) { throw 'RetentionDays must be at least 7.' }
$infra = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backupRoot = [IO.Path]::GetFullPath((Join-Path $infra 'backups'))
& (Join-Path $PSScriptRoot 'backup.ps1') -IncludeNeo4jDump
if ($LASTEXITCODE -ne 0) { throw 'New backup failed; no old backups will be removed.' }
$complete = @(Get-ChildItem -LiteralPath $backupRoot -Directory | Where-Object {
    $_.Name -match '^\d{8}-\d{6}$' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'backup.json')) -and
    -not (Test-Path -LiteralPath (Join-Path $_.FullName 'backup.failed'))
} | Sort-Object LastWriteTime -Descending)
foreach ($directory in ($complete | Select-Object -Skip 2)) {
    if ($directory.LastWriteTime -ge (Get-Date).AddDays(-$RetentionDays)) { continue }
    $resolvedTarget = [IO.Path]::GetFullPath($directory.FullName)
    if ((-not $resolvedTarget.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe backup cleanup target.' }
    if (Test-Path -LiteralPath (Join-Path $resolvedTarget 'legal-hold')) { continue }
    Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
    Write-Host "Removed expired successful backup: $($directory.Name)"
}
