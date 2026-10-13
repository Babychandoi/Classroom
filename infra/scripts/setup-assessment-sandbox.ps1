$ErrorActionPreference = 'Stop'
$envPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../.env'))
if (-not (Test-Path -LiteralPath $envPath)) { throw 'infra/.env is required.' }
$content = [IO.File]::ReadAllText($envPath)
if ($content -notmatch '(?m)^ASSESSMENT_SANDBOX_TOKEN=.{32,}$') {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    $token = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    $content = [regex]::Replace($content, '(?m)^ASSESSMENT_SANDBOX_TOKEN=.*\r?\n?', '')
    [IO.File]::WriteAllText($envPath, $content.TrimEnd() + "`nASSESSMENT_SANDBOX_TOKEN=$token`n", (New-Object Text.UTF8Encoding($false)))
}
foreach ($image in @('python:3.13-alpine','node:22-alpine')) {
    $existing = & docker image ls --quiet $image
    if (-not $existing) { & docker pull $image; if ($LASTEXITCODE -ne 0) { throw "Cannot pull worker image $image" } }
}
Write-Host 'Assessment sandbox token and worker images are ready.'
