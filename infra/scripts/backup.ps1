<#
.SYNOPSIS
  R13-11(b) (NFR-04/HLD S5), hardened by R14-06/07/08: back up MySQL, MongoDB, Neo4j and MinIO of one
  Compose project into a timestamped folder, with a verifiable manifest (backup.json).

.DESCRIPTION
  Containers are found through the Compose project (-ProjectName), so the same script backs up the
  live stack or a drill stack. Credentials are read from the target containers' own environment and
  are never placed on any command line (see infra/scripts/container/dbtool.sh).

  - MySQL:   mysqldump --single-transaction (full logical dump of the app database).
  - MongoDB: mongodump --archive --gzip of the app database.
  - Neo4j:   neo4j-admin database dump needs the database STOPPED, so it is skipped unless
             -IncludeNeo4jDump is given; then neo4j is stopped (a few seconds), dumped by a throw-away
             container that mounts its data volume and this backup folder, and started again.
  - MinIO:   `mc mirror` of the bucket via a one-off mc container on the project's network.

  Every component must succeed: any failure aborts the run with a non-zero exit code, the words
  "Backup complete" are NOT printed and a backup.failed marker is left in the folder. On success
  backup.json records timestamp, project, and per-component size + sha256; restore.ps1 verifies it.

.PARAMETER OutDir
  Base directory for the timestamped folder. Default: <InfraDir>\backups.
.PARAMETER ProjectName
  Compose project to back up. Default: the top-level `name:` of compose.yaml (the live stack).
.PARAMETER InfraDir
  Directory holding compose.yaml and .env. Default: the parent of this script's directory.
.PARAMETER EnvFile
  Env file(s) passed to `docker compose --env-file` (repeatable). Default: <InfraDir>\.env.
.PARAMETER OverlayFile
  Extra compose file(s), e.g. infra\compose.drill.yaml when backing up a drill stack.
.PARAMETER MysqlContainer
  Optional explicit container name/id overriding the compose-project lookup (same for -MongoContainer,
  -Neo4jContainer and -MinioContainer).
.PARAMETER Bucket
  MinIO bucket. Default: MINIO_BUCKET from the env file, else classroom-media.
.PARAMETER IncludeNeo4jDump
  Stop neo4j briefly and include its dump.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\backup.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\backup.ps1 -IncludeNeo4jDump -OutDir D:\Backups
#>
param(
    [string]$OutDir,
    [string]$ProjectName,
    [string]$InfraDir,
    [string[]]$EnvFile,
    [string[]]$OverlayFile,
    [string]$MysqlContainer,
    [string]$MongoContainer,
    [string]$Neo4jContainer,
    [string]$MinioContainer,
    [string]$Bucket,
    [string]$McImage,
    [switch]$IncludeNeo4jDump
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$BackupDir = $null
try {
    if (-not $InfraDir) { $InfraDir = Join-Path $PSScriptRoot ".." }
    $InfraDir = Get-AbsolutePath $InfraDir
    $ComposeFile = Join-Path $InfraDir "compose.yaml"
    if (-not (Test-Path -LiteralPath $ComposeFile)) { throw "compose.yaml not found in $InfraDir" }
    if (-not $EnvFile) { $EnvFile = @(Join-Path $InfraDir ".env") }
    $EnvFile = @($EnvFile | ForEach-Object { Get-AbsolutePath $_ })
    foreach ($f in $EnvFile) {
        if (-not (Test-Path -LiteralPath $f)) { throw "Env file not found: $f (copy .env.example and fill in credentials first)." }
    }
    $OverlayFile = @($OverlayFile | Where-Object { $_ } | ForEach-Object { Get-AbsolutePath $_ })
    if (-not $ProjectName) { $ProjectName = Get-ComposeFileProjectName $ComposeFile }
    if (-not $OutDir) { $OutDir = Join-Path $InfraDir "backups" }
    $OutDir = Get-AbsolutePath $OutDir
    if (-not $McImage) { $McImage = $script:DefaultMcImage }

    $envValues = @{}
    foreach ($f in $EnvFile) { foreach ($kv in (Read-EnvFile $f).GetEnumerator()) { $envValues[$kv.Key] = $kv.Value } }
    if (-not $Bucket) { $Bucket = $envValues["MINIO_BUCKET"] }
    if (-not $Bucket) { $Bucket = "classroom-media" }

    $ComposeArgs = @(New-ComposeArgs -Project $ProjectName -ComposeFile $ComposeFile -Overlays $OverlayFile -EnvFiles $EnvFile)

    $mysqlId = Get-ServiceContainerId -ComposeArgs $ComposeArgs -Service "mysql" -Override $MysqlContainer
    $mongoId = Get-ServiceContainerId -ComposeArgs $ComposeArgs -Service "mongodb" -Override $MongoContainer
    $minioId = Get-ServiceContainerId -ComposeArgs $ComposeArgs -Service "minio" -Override $MinioContainer
    $neo4jId = $null
    if ($IncludeNeo4jDump) { $neo4jId = Get-ServiceContainerId -ComposeArgs $ComposeArgs -Service "neo4j" -Override $Neo4jContainer }

    $mysqlInfo = Get-ContainerInfo $mysqlId
    $mongoInfo = Get-ContainerInfo $mongoId
    Assert-ContainerRunning $mysqlInfo "MySQL"
    Assert-ContainerRunning $mongoInfo "MongoDB"
    $mysqlDatabase = Get-ContainerEnvValue $mysqlInfo "MYSQL_DATABASE"
    $mongoDatabase = Get-ContainerEnvValue $mongoInfo "MONGO_INITDB_DATABASE"
    if (-not $mysqlDatabase) { throw "MySQL container has no MYSQL_DATABASE in its environment." }
    if (-not $mongoDatabase) { throw "MongoDB container has no MONGO_INITDB_DATABASE in its environment." }

    $Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $BackupDir = Join-Path $OutDir $Timestamp
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    Write-Host "Backup folder: $BackupDir"
    Write-Host "Compose project: $ProjectName (mysql=$(Get-ContainerName $mysqlInfo), mongodb=$(Get-ContainerName $mongoInfo))"
    $components = New-Object System.Collections.Generic.List[object]

    # -----------------------------------------------------------------------
    # 1. MySQL
    # -----------------------------------------------------------------------
    Write-Step "Backing up MySQL ($mysqlDatabase)..."
    $mysqlFileName = "mysql-$mysqlDatabase.sql"
    $mysqlHelper = Install-ContainerHelper $mysqlId "backup-$Timestamp"
    $mysqlTmp = "/tmp/classroom-backup-$Timestamp.sql"
    try {
        Invoke-Native "docker" @("exec", $mysqlId, "sh", $mysqlHelper, "mysql-dump", $mysqlTmp)
        Invoke-Native "docker" @("cp", "${mysqlId}:$mysqlTmp", (Join-Path $BackupDir $mysqlFileName))
    } finally {
        Remove-ContainerFiles $mysqlId @($mysqlTmp, $mysqlHelper)
    }
    # mysqldump ends a complete dump with "-- Dump completed"; a truncated file must not be trusted.
    $tail = @(Get-Content -LiteralPath (Join-Path $BackupDir $mysqlFileName) -Tail 5) -join "`n"
    if ($tail -notmatch "Dump completed") { throw "MySQL dump looks truncated (no 'Dump completed' trailer)." }
    $components.Add((New-FileComponent "mysql" $BackupDir $mysqlFileName))
    Write-Info "-> $mysqlFileName ($($components[$components.Count - 1].Bytes) bytes)"

    # -----------------------------------------------------------------------
    # 2. MongoDB
    # -----------------------------------------------------------------------
    Write-Step "Backing up MongoDB ($mongoDatabase)..."
    $mongoFileName = "mongodb-$mongoDatabase.archive.gz"
    $mongoHelper = Install-ContainerHelper $mongoId "backup-$Timestamp"
    $mongoTmp = "/tmp/classroom-backup-$Timestamp.archive.gz"
    try {
        Invoke-Native "docker" @("exec", $mongoId, "sh", $mongoHelper, "mongo-dump", $mongoTmp)
        Invoke-Native "docker" @("cp", "${mongoId}:$mongoTmp", (Join-Path $BackupDir $mongoFileName))
    } finally {
        Remove-ContainerFiles $mongoId @($mongoTmp, $mongoHelper)
    }
    $components.Add((New-FileComponent "mongodb" $BackupDir $mongoFileName))
    Write-Info "-> $mongoFileName ($($components[$components.Count - 1].Bytes) bytes)"

    # -----------------------------------------------------------------------
    # 3. Neo4j (offline dump, R14-06)
    # -----------------------------------------------------------------------
    if ($IncludeNeo4jDump) {
        Write-Step "Backing up Neo4j (stopping the container for an offline dump)..."
        $neo4jWasRunning = (Get-ContainerInfo $neo4jId).State.Running
        if ($neo4jWasRunning) { Invoke-Native "docker" @("stop", $neo4jId) }
        try {
            # ONE run (the old script dumped twice and threw the first result away), writing straight
            # into the backup folder through a bind mount: no docker cp, nothing lost with a --rm
            # container. A scratch sub-folder keeps the mount to just this dump.
            $scratch = Join-Path $BackupDir ".neo4j-dump"
            New-Item -ItemType Directory -Force -Path $scratch | Out-Null
            Invoke-Neo4jAdmin -Neo4jContainerId $neo4jId -HostDir $scratch -AdminArgs @("database", "dump", "neo4j", "--to-path=/backups")
            $dumpPath = Join-Path $scratch "neo4j.dump"
            if (-not (Test-Path -LiteralPath $dumpPath)) { throw "neo4j-admin reported success but produced no neo4j.dump." }
            Move-Item -LiteralPath $dumpPath -Destination (Join-Path $BackupDir "neo4j.dump")
            Remove-Item -LiteralPath $scratch -Force -Recurse
        } finally {
            if ($neo4jWasRunning) {
                Invoke-Native "docker" @("start", $neo4jId)
                if (-not (Wait-ContainerHealthy $neo4jId 180)) { Write-Warning "neo4j did not report healthy within 180s after restart." }
            }
        }
        $components.Add((New-FileComponent "neo4j" $BackupDir "neo4j.dump"))
        Write-Info "-> neo4j.dump ($($components[$components.Count - 1].Bytes) bytes)"
    } else {
        Write-Step "Skipping Neo4j dump (neo4j-admin database dump requires the database to be stopped)."
        Write-Info "Pass -IncludeNeo4jDump to stop/dump/restart it here, or see docs/RUNBOOK.md section 5.4."
    }

    # -----------------------------------------------------------------------
    # 4. MinIO (any mc failure fails the whole backup)
    # -----------------------------------------------------------------------
    Write-Step "Backing up MinIO bucket ($Bucket)..."
    $minioDirName = "minio-$Bucket"
    $minioDir = Join-Path $BackupDir $minioDirName
    New-Item -ItemType Directory -Force -Path $minioDir | Out-Null
    Invoke-Mc -MinioContainerId $minioId -McImage $McImage -HostDir $minioDir -McArgs @("mirror", "--overwrite", "myminio/$Bucket", "/backup")
    $components.Add((New-TreeComponent "minio" $BackupDir $minioDirName))
    Write-Info "-> $minioDirName ($($components[$components.Count - 1].Files) files, $($components[$components.Count - 1].Bytes) bytes)"

    # -----------------------------------------------------------------------
    # 5. Manifest + self-check
    # -----------------------------------------------------------------------
    Write-Step "Writing and verifying $($script:ManifestName)..."
    Write-BackupManifest -BackupDir $BackupDir -Project $ProjectName -Timestamp $Timestamp -Components $components.ToArray()
    [void](Test-BackupManifest $BackupDir)

    Write-Host ""
    Write-Host "Backup complete: $BackupDir"
    Write-Host "See docs/RUNBOOK.md section 5 for the restore drill."
    exit 0
} catch {
    [Console]::Error.WriteLine("Backup FAILED: " + $_.Exception.Message)
    if ($BackupDir -and (Test-Path -LiteralPath $BackupDir)) {
        try {
            [System.IO.File]::WriteAllText((Join-Path $BackupDir "backup.failed"), ("Backup failed: " + $_.Exception.Message + "`n"))
            [Console]::Error.WriteLine("This folder is INCOMPLETE and has no backup.json; do not restore from it: $BackupDir")
        } catch { }
    }
    exit 1
}
