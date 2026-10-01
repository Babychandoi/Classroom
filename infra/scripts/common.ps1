# common.ps1 - helpers shared by backup.ps1 and restore.ps1 (dot-sourced; not meant to be run directly).
#
# Windows PowerShell 5.1 compatible and pure ASCII on purpose: 5.1 misparses a BOM-less UTF-8 script
# that contains non-ASCII characters. Avoid the automatic variable names ($Args, $Input, $Error, ...).
#
# Design notes (R14-06/07/08):
#  - Containers are resolved through the Compose PROJECT (`docker compose -p <project> ps`), never by a
#    hard-coded container_name, so the same scripts serve the live stack and a drill stack.
#  - Database/MinIO credentials are read from the target containers' own environment (the values
#    compose passed to them), so they always match the target and never travel on a command line.

$script:DefaultMcImage = "quay.io/minio/mc:RELEASE.2024-05-09T17-04-24Z"
$script:HelperScriptSource = Join-Path $PSScriptRoot "container\dbtool.sh"
$script:ManifestName = "backup.json"

function Write-Step([string]$Message) { Write-Host "==> $Message" }
function Write-Info([string]$Message) { Write-Host "    $Message" }

function Get-AbsolutePath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $Path))
}

# Runs a native command, streams its stdout to the console and throws on a non-zero exit code.
function Invoke-Native {
    param([Parameter(Mandatory = $true)][string]$Exe, [string[]]$CmdArgs = @())
    & $Exe @CmdArgs | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "'$Exe $($CmdArgs -join ' ')' failed with exit code $LASTEXITCODE" }
}

# Runs a native command and returns its stdout lines; throws on a non-zero exit code.
function Get-NativeOutput {
    param([Parameter(Mandatory = $true)][string]$Exe, [string[]]$CmdArgs = @())
    $output = & $Exe @CmdArgs
    if ($LASTEXITCODE -ne 0) { throw "'$Exe $($CmdArgs -join ' ')' failed with exit code $LASTEXITCODE" }
    if ($null -eq $output) { return @() }
    return @($output)
}

# Parses a KEY=VALUE env file (comments, blank lines, optional export prefix and quotes handled).
function Read-EnvFile([string]$Path) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    foreach ($raw in (Get-Content -LiteralPath $Path)) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith("#")) { continue }
        if ($line.StartsWith("export ")) { $line = $line.Substring(7).Trim() }
        $idx = $line.IndexOf("=")
        if ($idx -lt 1) { continue }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $map[$key] = $value
    }
    return $map
}

# Top-level `name:` of a compose file (the project name Compose uses when -p is not given).
function Get-ComposeFileProjectName([string]$ComposeFile) {
    foreach ($line in (Get-Content -LiteralPath $ComposeFile -TotalCount 20)) {
        if ($line -match '^name:\s*(\S+)\s*$') { return $Matches[1] }
    }
    return "online-classroom"
}

# Builds the `docker compose ...` prefix arguments for one project.
function New-ComposeArgs {
    param([string]$Project, [string]$ComposeFile, [string[]]$Overlays, [string[]]$EnvFiles)
    $composeArgs = @("compose", "-p", $Project, "-f", $ComposeFile)
    foreach ($overlay in $Overlays) { if ($overlay) { $composeArgs += @("-f", $overlay) } }
    foreach ($envFile in $EnvFiles) { if ($envFile) { $composeArgs += @("--env-file", $envFile) } }
    return $composeArgs
}

function Get-ContainerInfo([string]$ContainerId) {
    $json = (@(Get-NativeOutput "docker" @("inspect", $ContainerId)) -join "`n")
    $parsed = $json | ConvertFrom-Json
    if ($parsed -is [array]) { return $parsed[0] }
    return $parsed
}

# Resolves the container of a compose service in the target project (or an explicit override name/id).
# Returns the full container id, or $null when the service has no container and -Optional is set.
function Get-ServiceContainerId {
    param([string[]]$ComposeArgs, [string]$Service, [string]$Override, [switch]$Optional)
    if ($Override) {
        return ([string](@(Get-NativeOutput "docker" @("inspect", "--format", "{{.Id}}", $Override))[0])).Trim()
    }
    $ids = @(Get-NativeOutput "docker" ($ComposeArgs + @("ps", "-a", "-q", $Service)) | Where-Object { $_ -and $_.Trim() })
    if ($ids.Count -eq 0) {
        if ($Optional) { return $null }
        throw "No container found for service '$Service' in the target compose project. Is the stack up?"
    }
    if ($ids.Count -gt 1) { throw "More than one container found for service '$Service'; pass an explicit container override." }
    return $ids[0].Trim()
}

function Get-ContainerName($Info) { return $Info.Name.TrimStart("/") }

function Get-ContainerEnvValue($Info, [string]$Name) {
    foreach ($entry in $Info.Config.Env) {
        if ($entry.StartsWith("$Name=")) { return $entry.Substring($Name.Length + 1) }
    }
    return $null
}

function Assert-ContainerRunning($Info, [string]$What) {
    if (-not $Info.State.Running) { throw "$What container '$(Get-ContainerName $Info)' is not running." }
}

function Wait-ContainerHealthy([string]$ContainerId, [int]$TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $info = Get-ContainerInfo $ContainerId
        if ($info.State.Running) {
            $health = $info.State.Health
            if ($null -eq $health -or $health.Status -eq "healthy") { return $true }
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

# ---------------------------------------------------------------------------
# In-container helper (infra/scripts/container/dbtool.sh) - see that file for why it exists.
# ---------------------------------------------------------------------------
function Install-ContainerHelper([string]$ContainerId, [string]$Suffix) {
    $target = "/tmp/classroom-dbtool-$Suffix.sh"
    # Normalise to LF so a CRLF checkout cannot break `sh`.
    $text = [System.IO.File]::ReadAllText($script:HelperScriptSource).Replace("`r`n", "`n")
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("classroom-dbtool-" + [Guid]::NewGuid().ToString("N") + ".sh")
    try {
        [System.IO.File]::WriteAllText($temp, $text, (New-Object System.Text.UTF8Encoding($false)))
        Invoke-Native "docker" @("cp", $temp, "${ContainerId}:$target")
    } finally {
        Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
    }
    return $target
}

# Best-effort removal of temporary files inside a container (never throws).
function Remove-ContainerFiles([string]$ContainerId, [string[]]$Paths) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & docker exec $ContainerId rm -f @Paths 2>&1 | Out-Null
    } catch {
        # Cleanup only; the caller's real error (if any) is what matters.
    } finally {
        $ErrorActionPreference = $previous
        $global:LASTEXITCODE = 0
    }
}

# ---------------------------------------------------------------------------
# Hashing + manifest (backup.json)
# ---------------------------------------------------------------------------
function Get-Sha256Hex([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

# Canonical "tree digest" (also implemented in common.sh so either script family can verify the
# other's backups): sha256 over the text of lines "<file sha256>  <relative path with />\n", one per
# file, sorted by relative path in ordinal (byte) order.
function Get-TreeDigest([string]$Dir) {
    $root = (Resolve-Path -LiteralPath $Dir).ProviderPath.TrimEnd([char]92, [char]47)
    $sorted = New-Object "System.Collections.Generic.SortedDictionary[string,string]" -ArgumentList ([System.StringComparer]::Ordinal)
    $bytes = [int64]0
    foreach ($file in (Get-ChildItem -LiteralPath $root -Recurse -File -Force)) {
        $rel = $file.FullName.Substring($root.Length + 1).Replace("\", "/")
        $sorted[$rel] = Get-Sha256Hex $file.FullName
        $bytes += $file.Length
    }
    $sb = New-Object System.Text.StringBuilder
    foreach ($key in $sorted.Keys) { [void]$sb.Append($sorted[$key]).Append("  ").Append($key).Append("`n") }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($sb.ToString()))
    $hex = (($hash | ForEach-Object { $_.ToString("x2") }) -join "")
    return @{ Files = $sorted.Count; Bytes = $bytes; Sha256 = $hex }
}

function New-FileComponent([string]$Name, [string]$BackupDir, [string]$RelativePath) {
    $full = Join-Path $BackupDir $RelativePath
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "Component '$Name' produced no file at $full" }
    $length = (Get-Item -LiteralPath $full).Length
    if ($length -le 0) { throw "Component '$Name' file is empty: $full" }
    return @{ Name = $Name; Type = "file"; Path = $RelativePath; Bytes = [int64]$length; Files = 1; Sha256 = (Get-Sha256Hex $full) }
}

function New-TreeComponent([string]$Name, [string]$BackupDir, [string]$RelativePath) {
    $full = Join-Path $BackupDir $RelativePath
    if (-not (Test-Path -LiteralPath $full -PathType Container)) { throw "Component '$Name' produced no directory at $full" }
    $digest = Get-TreeDigest $full
    return @{ Name = $Name; Type = "tree"; Path = $RelativePath; Bytes = [int64]$digest.Bytes; Files = [int]$digest.Files; Sha256 = $digest.Sha256 }
}

function ConvertTo-JsonString([string]$Value) {
    return '"' + $Value.Replace("\", "\\").Replace('"', '\"') + '"'
}

# One component per line on purpose: common.sh parses the manifest with sed, no jq required.
function Write-BackupManifest {
    param([string]$BackupDir, [string]$Project, [string]$Timestamp, [object[]]$Components)
    $lines = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $Components.Count; $i++) {
        $c = $Components[$i]
        $comma = ""
        if ($i -lt $Components.Count - 1) { $comma = "," }
        $lines.Add(('    {{"name": {0}, "type": {1}, "path": {2}, "bytes": {3}, "files": {4}, "sha256": {5}}}{6}' -f `
            (ConvertTo-JsonString $c.Name), (ConvertTo-JsonString $c.Type), (ConvertTo-JsonString $c.Path), `
            $c.Bytes, $c.Files, (ConvertTo-JsonString $c.Sha256), $comma))
    }
    $created = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $text = "{`n" +
        '  "version": 1,' + "`n" +
        '  "timestamp": ' + (ConvertTo-JsonString $Timestamp) + ",`n" +
        '  "createdUtc": ' + (ConvertTo-JsonString $created) + ",`n" +
        '  "project": ' + (ConvertTo-JsonString $Project) + ",`n" +
        '  "components": [' + "`n" +
        ($lines -join "`n") + "`n" +
        "  ]`n}`n"
    [System.IO.File]::WriteAllText((Join-Path $BackupDir $script:ManifestName), $text, (New-Object System.Text.UTF8Encoding($false)))
}

# Verifies every component listed in backup.json against the files on disk. Returns the parsed
# manifest; throws on the first missing/corrupt component. Reads only - changes nothing.
function Test-BackupManifest([string]$BackupDir) {
    $manifestPath = Join-Path $BackupDir $script:ManifestName
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        throw "No $($script:ManifestName) in $BackupDir - not a complete backup made by backup.ps1/backup.sh (pass -SkipManifestVerify only for a legacy backup you trust)."
    }
    $manifest = [System.IO.File]::ReadAllText($manifestPath) | ConvertFrom-Json
    if ($manifest.version -ne 1) { throw "Unsupported manifest version '$($manifest.version)'." }
    $names = @($manifest.components | ForEach-Object { $_.name })
    if ($names.Count -eq 0) { throw "Manifest lists no components." }
    foreach ($c in $manifest.components) {
        $full = Join-Path $BackupDir $c.path
        if ($c.type -eq "file") {
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "Component '$($c.name)': file missing: $($c.path)" }
            $length = (Get-Item -LiteralPath $full).Length
            if ($length -ne $c.bytes) { throw "Component '$($c.name)': size $length != manifest $($c.bytes) ($($c.path))" }
            $hash = Get-Sha256Hex $full
            if ($hash -ne $c.sha256) { throw "Component '$($c.name)': sha256 mismatch for $($c.path)" }
        } elseif ($c.type -eq "tree") {
            if (-not (Test-Path -LiteralPath $full -PathType Container)) { throw "Component '$($c.name)': directory missing: $($c.path)" }
            $digest = Get-TreeDigest $full
            if ($digest.Files -ne $c.files -or $digest.Bytes -ne $c.bytes -or $digest.Sha256 -ne $c.sha256) {
                throw "Component '$($c.name)': directory content differs from manifest ($($c.path))"
            }
        } else {
            throw "Component '$($c.name)': unknown type '$($c.type)'"
        }
        Write-Info ("verified {0,-8} {1} ({2} bytes, sha256 {3}...)" -f $c.name, $c.path, $c.bytes, $c.sha256.Substring(0, 12))
    }
    return $manifest
}

# ---------------------------------------------------------------------------
# Neo4j offline dump/load in a throw-away container that bind-mounts a HOST directory (R14-06)
# ---------------------------------------------------------------------------
# neo4j-admin database dump/load needs the database stopped and cannot run inside the stopped
# service container (`docker exec` needs a running container; `docker compose run` starts a NEW
# container whose /tmp is empty). So: run the SAME image with the SAME /data volume and bind-mount the
# backup directory, so the dump is written straight to (or read straight from) the host.
function Invoke-Neo4jAdmin {
    param([string]$Neo4jContainerId, [string]$HostDir, [string[]]$AdminArgs, [switch]$ReadOnlyMount)
    $info = Get-ContainerInfo $Neo4jContainerId
    $image = $info.Config.Image
    $mount = $info.Mounts | Where-Object { $_.Destination -eq "/data" } | Select-Object -First 1
    if (-not $mount) { throw "Neo4j container '$(Get-ContainerName $info)' has no /data mount." }
    $source = $mount.Source
    if ($mount.Name) { $source = $mount.Name }
    $hostMount = ($HostDir.Replace("\", "/")) + ":/backups"
    if ($ReadOnlyMount) { $hostMount += ":ro" }
    Invoke-Native "docker" (@("run", "--rm", "-v", "${source}:/data", "-v", $hostMount, $image, "neo4j-admin") + $AdminArgs)
}

# ---------------------------------------------------------------------------
# MinIO via a one-off mc container on the target project's network
# ---------------------------------------------------------------------------
function Invoke-Mc {
    param([string]$MinioContainerId, [string]$McImage, [string]$HostDir, [string[]]$McArgs, [switch]$ReadOnlyMount)
    $info = Get-ContainerInfo $MinioContainerId
    Assert-ContainerRunning $info "MinIO"
    $user = Get-ContainerEnvValue $info "MINIO_ROOT_USER"
    $pass = Get-ContainerEnvValue $info "MINIO_ROOT_PASSWORD"
    if (-not $user -or -not $pass) { throw "MinIO container does not expose MINIO_ROOT_USER/MINIO_ROOT_PASSWORD." }
    $network = @($info.NetworkSettings.Networks.PSObject.Properties.Name)[0]
    if (-not $network) { throw "Cannot determine the MinIO container's network." }
    # The credentials go through the process environment (`-e NAME` copies it), so they are never on
    # the docker command line.
    $env:MC_HOST_myminio = "http://" + [Uri]::EscapeDataString($user) + ":" + [Uri]::EscapeDataString($pass) + "@minio:9000"
    try {
        $dockerArgs = @("run", "--rm", "--network", $network, "-e", "MC_HOST_myminio")
        if ($HostDir) {
            $mountSpec = ($HostDir.Replace("\", "/")) + ":/backup"
            if ($ReadOnlyMount) { $mountSpec += ":ro" }
            $dockerArgs += @("-v", $mountSpec)
        }
        Invoke-Native "docker" ($dockerArgs + @($McImage) + $McArgs)
    } finally {
        Remove-Item Env:\MC_HOST_myminio -ErrorAction SilentlyContinue
    }
}
