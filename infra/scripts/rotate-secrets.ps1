param([string]$InfraDir = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$InfraDir = Get-AbsolutePath $InfraDir
$envPath = Join-Path $InfraDir '.env'
$pendingPath = Join-Path $InfraDir 'rotation.pending.env'
if (Test-Path -LiteralPath $pendingPath) { throw 'Unfinished rotation exists. Recover using rotation.pending.env before starting another rotation.' }
$values = Read-EnvFile $envPath
function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}
$keys = @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','MONGO_INITDB_ROOT_PASSWORD','NEO4J_PASSWORD','MINIO_ROOT_PASSWORD','JWT_SECRET','MOCK_PAYMENT_WEBHOOK_SECRET')
foreach ($key in $keys) { $values[$key] = New-RandomSecret }
$text = Get-Content -LiteralPath $envPath -Raw
foreach ($key in $keys) {
    $pattern = '(?m)^' + [regex]::Escape($key) + '=.*$'
    if ($text -notmatch $pattern) { throw "Missing key: $key" }
    $text = [regex]::Replace($text, $pattern, "$key=$($values[$key])")
}
# Save the full recovery configuration BEFORE changing any database. Never print credentials.
[IO.File]::WriteAllText($pendingPath, $text, (New-Object Text.UTF8Encoding($false)))
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $pendingPath /inheritance:r /grant:r "${identity}:(F)" '*S-1-5-18:(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot protect pending secret file.' }
function Invoke-PrivateInput([string]$Container, [string[]]$Command, [string]$Payload) {
    $Payload | & docker exec -i $Container @Command 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Credential rotation failed for $Container. Recovery secrets are in rotation.pending.env; do not delete it." }
}
foreach ($prefix in @('classroom','classroom-demo')) {
    $mysql = Get-ContainerInfo "$prefix-mysql"
    $user = Get-ContainerEnvValue $mysql 'MYSQL_USER'
    if ($user -notmatch '^[a-zA-Z0-9_]+$') { throw 'Unsupported MySQL account name.' }
    $hosts = @("SELECT Host FROM mysql.user WHERE User = 'root';" | & docker exec -i "$prefix-mysql" sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD exec mysql -uroot -N -B' 2>$null)
    if ($LASTEXITCODE -ne 0 -or $hosts.Count -eq 0) { throw 'Cannot discover MySQL root accounts.' }
    $sql = "ALTER USER '$user'@'%' IDENTIFIED BY '$($values['MYSQL_PASSWORD'])';"
    foreach ($hostValue in $hosts) {
        if ($hostValue -notmatch '^[a-zA-Z0-9.%:_-]+$') { throw 'Unsupported MySQL account host.' }
        $sql += "`nALTER USER 'root'@'$hostValue' IDENTIFIED BY '$($values['MYSQL_ROOT_PASSWORD'])';"
    }
    Invoke-PrivateInput "$prefix-mysql" @('sh','-c','MYSQL_PWD=$MYSQL_ROOT_PASSWORD exec mysql -uroot') $sql
    $mongoCode = 'const admin = db.getSiblingDB("admin"); if (!admin.auth(process.env.MONGO_INITDB_ROOT_USERNAME, process.env.MONGO_INITDB_ROOT_PASSWORD)) quit(1); admin.changeUserPassword(process.env.MONGO_INITDB_ROOT_USERNAME, "' + $values['MONGO_INITDB_ROOT_PASSWORD'] + '"); quit(0);'
    Invoke-PrivateInput "$prefix-mongodb" @('mongosh','--quiet') $mongoCode
    $neoInfo = Get-ContainerInfo "$prefix-neo4j"
    $oldNeo = (Get-ContainerEnvValue $neoInfo 'NEO4J_AUTH').Substring(6).Replace("'", "\'")
    $cypher = "ALTER CURRENT USER SET PASSWORD FROM '$oldNeo' TO '$($values['NEO4J_PASSWORD'])';"
    Invoke-PrivateInput "$prefix-neo4j" @('sh','-c','export NEO4J_USERNAME=neo4j; export NEO4J_PASSWORD=${NEO4J_AUTH#neo4j/}; exec cypher-shell --non-interactive') $cypher
    Write-Host "Rotated persisted database accounts: $prefix"
}
[IO.File]::WriteAllText($envPath, $text, (New-Object Text.UTF8Encoding($false)))
& icacls $envPath /inheritance:r /grant:r "${identity}:(F)" '*S-1-5-18:(F)' | Out-Null
Remove-Item -LiteralPath $pendingPath
Write-Host 'Saved independent 64-character secrets to infra/.env. Recreate both stacks now; MinIO/JWT use the new values on restart.'
