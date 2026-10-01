<#
.SYNOPSIS
  R13-11(b) (NFR-04/HLD S5), hardened by R14-06/07/08: restore MySQL, MongoDB, Neo4j and MinIO of ONE
  explicitly named Compose project from a backup folder made by backup.ps1 / backup.sh.

.DESCRIPTION
  DESTRUCTIVE. The target is never implicit and nothing is hard-coded to the live stack:

    * -ProjectName is mandatory (e.g. classroom-drill for a drill stack, online-classroom for the
      live stack) and containers are resolved through that Compose project.
    * The run REFUSES to start unless -Force is given AND the project name is confirmed: either by
      typing it at the prompt (interactive) or by passing -ConfirmProject <name> (non-interactive);
      the two names must match exactly.
    * The backup is verified against backup.json (size + sha256 of every component) BEFORE anything
      is touched. -VerifyOnly stops after that check and needs neither -Force nor a running stack.
    * The backend (the datastore writer) is stopped before MySQL/MongoDB/MinIO are overwritten and
      started again afterwards. If the restore FAILS the writer is left stopped on purpose (do not
      run the app against half-restored data) and the exit code is non-zero.
    * MySQL is restored as a full replace (all tables of the app database are dropped first).
      MongoDB uses mongorestore --drop (collections present in the archive are replaced).
      MinIO objects are copied over the bucket; objects created AFTER the backup survive unless
      -MirrorRemove is given (mc mirror --remove), which makes the bucket equal to the backup.

  Credentials come from the target containers' own environment and never appear on a command line.

.PARAMETER BackupDir
  Timestamped folder produced by backup.ps1 (contains backup.json).
.PARAMETER ProjectName
  Compose project to overwrite (required unless -VerifyOnly).
.PARAMETER Force
  Required acknowledgement that the target project's data will be overwritten.
.PARAMETER ConfirmProject
  Non-interactive confirmation: must equal -ProjectName. Without it the script asks you to type the
  project name (and refuses when there is no interactive console).
.PARAMETER InfraDir
  Directory holding compose.yaml and .env. Default: the parent of this script's directory.
.PARAMETER EnvFile
  Env file(s) for `docker compose --env-file` (repeatable). Default: <InfraDir>\.env.
.PARAMETER OverlayFile
  Extra compose file(s), e.g. infra\compose.drill.yaml for the drill stack.
.PARAMETER MysqlContainer
  Optional explicit container name/id overriding the project lookup (same for -MongoContainer,
  -Neo4jContainer, -MinioContainer, -BackendContainer).
.PARAMETER Bucket
  MinIO bucket to restore into. Default: MINIO_BUCKET from the env file, else classroom-media.
.PARAMETER IncludeNeo4j
  Also restore neo4j.dump (neo4j is stopped for the load and started again).
.PARAMETER MirrorRemove
  Delete bucket objects that are not in the backup (full-restore semantics).
.PARAMETER VerifyOnly
  Verify backup.json against the files and exit; changes nothing.
.PARAMETER SkipManifestVerify
  Restore a legacy backup that has no backup.json (files are found by name). Use only if you trust it.
.PARAMETER KeepWritersStopped
  Do not start the backend again after a successful restore.

.EXAMPLE
  # Verify a backup without touching any stack
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\restore.ps1 -BackupDir infra\backups\20260929-143000 -VerifyOnly
.EXAMPLE
  # Restore into the drill stack (interactive confirmation)
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\restore.ps1 -BackupDir infra\backups\20260929-143000 `
      -ProjectName classroom-drill -OverlayFile infra\compose.drill.yaml -IncludeNeo4j -MirrorRemove -Force
.EXAMPLE
  # Non-interactive
  ... -ProjectName classroom-drill -OverlayFile infra\compose.drill.yaml -Force -ConfirmProject classroom-drill
#>
param(
    [Parameter(Mandatory = $true)][string]$BackupDir,
    [string]$ProjectName,
    [switch]$Force,
    [string]$ConfirmProject,
    [string]$InfraDir,
    [string[]]$EnvFile,
    [string[]]$OverlayFile,
    [string]$MysqlContainer,
    [string]$MongoContainer,
    [string]$Neo4jContainer,
    [string]$MinioContainer,
    [string]$BackendContainer,
    [string]$Bucket,
    [string]$McImage,
    [switch]$IncludeNeo4j,
    [switch]$MirrorRemove,
    [switch]$VerifyOnly,
    [switch]$SkipManifestVerify,
    [switch]$KeepWritersStopped
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

# Services that write to the datastores; they are stopped during the restore.
$WriterServices = @("backend")
$stoppedWriters = New-Object System.Collections.Generic.List[string]
$restoreStarted = $false

try {
    if (-not (Test-Path -LiteralPath $BackupDir -PathType Container)) { throw "Backup folder not found: $BackupDir" }
    $BackupDir = (Resolve-Path -LiteralPath $BackupDir).ProviderPath.TrimEnd([char]92, [char]47)

    # ---------------------------------------------------------------- 1. verify the backup first
    Write-Step "Verifying backup $BackupDir"
    $manifest = $null
    if ($SkipManifestVerify) {
        Write-Warning "Manifest verification skipped (-SkipManifestVerify): the backup's integrity is NOT checked."
    } else {
        $manifest = Test-BackupManifest $BackupDir
        Write-Info ("manifest OK: project '{0}', created {1}" -f $manifest.project, $manifest.createdUtc)
    }

    # Component locations: from the manifest when present, otherwise by file name.
    $paths = @{}
    if ($manifest) {
        foreach ($c in $manifest.components) { $paths[$c.name] = Join-Path $BackupDir $c.path }
    } else {
        $mysqlFile = Get-ChildItem -LiteralPath $BackupDir -Filter "mysql-*.sql" -File | Select-Object -First 1
        $mongoFile = Get-ChildItem -LiteralPath $BackupDir -Filter "mongodb-*.archive.gz" -File | Select-Object -First 1
        $minioFolder = Get-ChildItem -LiteralPath $BackupDir -Filter "minio-*" -Directory | Select-Object -First 1
        if ($mysqlFile) { $paths["mysql"] = $mysqlFile.FullName }
        if ($mongoFile) { $paths["mongodb"] = $mongoFile.FullName }
        if ($minioFolder) { $paths["minio"] = $minioFolder.FullName }
        if (Test-Path -LiteralPath (Join-Path $BackupDir "neo4j.dump")) { $paths["neo4j"] = Join-Path $BackupDir "neo4j.dump" }
    }
    if ($IncludeNeo4j -and -not $paths.ContainsKey("neo4j")) {
        throw "-IncludeNeo4j given but this backup has no neo4j.dump (it was made without -IncludeNeo4jDump)."
    }

    if ($VerifyOnly) {
        Write-Host ""
        Write-Host "Verify-only: backup is intact. Nothing was changed."
        exit 0
    }

    # ---------------------------------------------------------------- 2. resolve the target
    if (-not $ProjectName) {
        throw "-ProjectName is required: name the Compose project to overwrite (e.g. classroom-drill). There is no default on purpose."
    }
    if (-not $InfraDir) { $InfraDir = Join-Path $PSScriptRoot ".." }
    $InfraDir = Get-AbsolutePath $InfraDir
    $ComposeFile = Join-Path $InfraDir "compose.yaml"
    if (-not (Test-Path -LiteralPath $ComposeFile)) { throw "compose.yaml not found in $InfraDir" }
    if (-not $EnvFile) { $EnvFile = @(Join-Path $InfraDir ".env") }
    $EnvFile = @($EnvFile | ForEach-Object { Get-AbsolutePath $_ })
    foreach ($f in $EnvFile) { if (-not (Test-Path -LiteralPath $f)) { throw "Env file not found: $f" } }
    $OverlayFile = @($OverlayFile | Where-Object { $_ } | ForEach-Object { Get-AbsolutePath $_ })
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
    if ($IncludeNeo4j) { $neo4jId = Get-ServiceContainerId -ComposeArgs $ComposeArgs -Service "neo4j" -Override $Neo4jContainer }
    $mysqlInfo = Get-ContainerInfo $mysqlId
    $mongoInfo = Get-ContainerInfo $mongoId
    $minioInfo = Get-ContainerInfo $minioId
    Assert-ContainerRunning $mysqlInfo "MySQL"
    Assert-ContainerRunning $mongoInfo "MongoDB"
    Assert-ContainerRunning $minioInfo "MinIO"

    Write-Host ""
    Write-Host "RESTORE TARGET"
    Write-Host "  compose project : $ProjectName"
    Write-Host "  mysql           : $(Get-ContainerName $mysqlInfo)"
    Write-Host "  mongodb         : $(Get-ContainerName $mongoInfo)"
    Write-Host "  minio           : $(Get-ContainerName $minioInfo) (bucket $Bucket$(if ($MirrorRemove) { ', objects not in the backup will be DELETED' } else { '' }))"
    if ($IncludeNeo4j) { Write-Host "  neo4j           : $((Get-ContainerName (Get-ContainerInfo $neo4jId)))" }
    Write-Host "  backup          : $BackupDir"
    Write-Host "This OVERWRITES the data of that project."

    # ---------------------------------------------------------------- 3. explicit confirmation
    if (-not $Force) {
        throw "Refusing to restore: pass -Force to acknowledge that project '$ProjectName' will be overwritten."
    }
    if ($ConfirmProject) {
        if ($ConfirmProject -cne $ProjectName) { throw "-ConfirmProject '$ConfirmProject' does not match -ProjectName '$ProjectName'." }
    } else {
        $interactive = [Environment]::UserInteractive -and (-not [Console]::IsInputRedirected)
        if (-not $interactive) {
            throw "No interactive console to confirm on: pass -ConfirmProject $ProjectName to confirm non-interactively."
        }
        $typed = Read-Host "Type the compose project name '$ProjectName' to confirm"
        if ($typed -cne $ProjectName) { throw "Confirmation did not match '$ProjectName'; nothing was changed." }
    }

    # ---------------------------------------------------------------- 4. quiesce writers
    $restoreStarted = $true
    Write-Step "Stopping datastore writers ($($WriterServices -join ', '))..."
    foreach ($service in $WriterServices) {
        $override = $null
        if ($service -eq "backend") { $override = $BackendContainer }
        $writerId = Get-ServiceContainerId -ComposeArgs $ComposeArgs -Service $service -Override $override -Optional
        if ($writerId -and (Get-ContainerInfo $writerId).State.Running) {
            Invoke-Native "docker" @("stop", $writerId)
            $stoppedWriters.Add($writerId)
        }
    }

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"

    # ---------------------------------------------------------------- 5. MySQL
    if ($paths.ContainsKey("mysql")) {
        $dumpFile = $paths["mysql"]
        Write-Step "Restoring MySQL from $dumpFile..."
        $tail = @(Get-Content -LiteralPath $dumpFile -Tail 5) -join "`n"
        if ($tail -notmatch "Dump completed") { throw "MySQL dump looks truncated (no 'Dump completed' trailer); not restoring." }
        $helper = Install-ContainerHelper $mysqlId "restore-$stamp"
        $tmp = "/tmp/classroom-restore-$stamp.sql"
        try {
            Invoke-Native "docker" @("cp", $dumpFile, "${mysqlId}:$tmp")
            Invoke-Native "docker" @("exec", $mysqlId, "sh", $helper, "mysql-restore", $tmp)
        } finally {
            Remove-ContainerFiles $mysqlId @($tmp, $helper)
        }
        Write-Info "MySQL restore complete."
    } else {
        Write-Warning "No MySQL dump in this backup - skipping."
    }

    # ---------------------------------------------------------------- 6. MongoDB
    if ($paths.ContainsKey("mongodb")) {
        $archive = $paths["mongodb"]
        Write-Step "Restoring MongoDB from $archive..."
        $helper = Install-ContainerHelper $mongoId "restore-$stamp"
        $tmp = "/tmp/classroom-restore-$stamp.archive.gz"
        try {
            Invoke-Native "docker" @("cp", $archive, "${mongoId}:$tmp")
            Invoke-Native "docker" @("exec", $mongoId, "sh", $helper, "mongo-restore", $tmp)
        } finally {
            Remove-ContainerFiles $mongoId @($tmp, $helper)
        }
        Write-Info "MongoDB restore complete."
    } else {
        Write-Warning "No MongoDB archive in this backup - skipping."
    }

    # ---------------------------------------------------------------- 7. Neo4j (offline load, R14-06)
    if ($IncludeNeo4j) {
        Write-Step "Restoring Neo4j from $($paths['neo4j']) (stopping the container for an offline load)..."
        $neo4jWasRunning = (Get-ContainerInfo $neo4jId).State.Running
        if ($neo4jWasRunning) { Invoke-Native "docker" @("stop", $neo4jId) }
        try {
            # The load runs in a NEW container on the same data volume that bind-mounts the folder
            # holding the dump (a docker cp into the stopped service container would not be visible
            # to a fresh `compose run` container). The mount is read-only.
            $neo4jDir = Split-Path -Parent $paths["neo4j"]
            if ((Split-Path -Leaf $paths["neo4j"]) -ne "neo4j.dump") { throw "The Neo4j dump must be named neo4j.dump." }
            Invoke-Neo4jAdmin -Neo4jContainerId $neo4jId -HostDir $neo4jDir -ReadOnlyMount `
                -AdminArgs @("database", "load", "neo4j", "--from-path=/backups", "--overwrite-destination=true")
        } finally {
            if ($neo4jWasRunning) {
                Invoke-Native "docker" @("start", $neo4jId)
                if (-not (Wait-ContainerHealthy $neo4jId 180)) { Write-Warning "neo4j did not report healthy within 180s after restart." }
            }
        }
        Write-Info "Neo4j restore complete."
    } else {
        Write-Step "Skipping Neo4j restore (pass -IncludeNeo4j to restore it; see docs/RUNBOOK.md)."
    }

    # ---------------------------------------------------------------- 8. MinIO
    if ($paths.ContainsKey("minio")) {
        $tree = $paths["minio"]
        Write-Step "Restoring MinIO bucket $Bucket from $tree..."
        Invoke-Mc -MinioContainerId $minioId -McImage $McImage -McArgs @("mb", "--ignore-existing", "myminio/$Bucket")
        $mirrorArgs = @("mirror", "--overwrite")
        if ($MirrorRemove) { $mirrorArgs += "--remove" }
        $mirrorArgs += @("/backup", "myminio/$Bucket")
        Invoke-Mc -MinioContainerId $minioId -McImage $McImage -HostDir $tree -ReadOnlyMount -McArgs $mirrorArgs
        Write-Info "MinIO restore complete."
    } else {
        Write-Warning "No MinIO folder in this backup - skipping."
    }

    # ---------------------------------------------------------------- 9. start writers again
    if ($KeepWritersStopped) {
        Write-Step "Leaving the writers stopped (-KeepWritersStopped)."
        foreach ($id in $stoppedWriters) { Write-Info "start later with: docker start $id" }
    } else {
        Write-Step "Starting datastore writers again..."
        foreach ($id in $stoppedWriters) {
            Invoke-Native "docker" @("start", $id)
            if (Wait-ContainerHealthy $id 180) { Write-Info "healthy: $id" } else { Write-Warning "container $id did not report healthy within 180s; check its logs." }
        }
    }

    Write-Host ""
    Write-Host "Restore complete for project '$ProjectName'."
    exit 0
} catch {
    [Console]::Error.WriteLine("Restore FAILED: " + $_.Exception.Message)
    if ($restoreStarted -and $stoppedWriters.Count -gt 0) {
        [Console]::Error.WriteLine("The datastore writers were left STOPPED on purpose (data may be half-restored). Fix the problem and re-run,")
        [Console]::Error.WriteLine("or start them manually once you have checked the data: docker start " + ($stoppedWriters -join " "))
    }
    exit 1
}
