<#
.SYNOPSIS
  ONE command on a (Windows) server: unpack a migration bundle, build and start the whole Classroom
  stack and restore the data (MySQL, MongoDB, Neo4j, MinIO). PowerShell twin of install.sh.

.DESCRIPTION
  Windows PowerShell 5.1 compatible, pure ASCII. Needs Docker Desktop (WSL2 backend, >= 6 GB RAM for
  the VM - see README-MIGRATION.md for .wslconfig), Docker Compose v2 and tar.exe (built into
  Windows 10 1803+). No openssl needed: encrypted bundles (aes-256-cbc / pbkdf2-sha256, 200000
  iterations, the format `openssl enc` writes) are decrypted with .NET.

  Modes
    Bundle mode  : .\install.ps1 classroom-bundle-<ts>.tar.gz[.enc] -Dir C:\classroom
    Clone mode   : run from a cloned repo with NO bundle: fresh EMPTY stack, infra\.env is created
                   from .env.example with random secrets, no demo unless -Demo.

.PARAMETER Bundle      Bundle file (default: newest classroom-bundle-*.tar.gz* next to this script).
.PARAMETER Dir         Install location (default .\classroom). Clone mode installs in place.
.PARAMETER Project     Compose project name (default: the one in the bundle, normally classroom-demo).
.PARAMETER Port        Public frontend port (default: FRONTEND_PORT of the bundle env).
.PARAMETER Origin      Public origin, e.g. https://lop.example.com (CORS; Secure cookie when https).
.PARAMETER Bind        HOST_BIND_ADDRESS: 127.0.0.1 (default) or 0.0.0.0 to expose frontend+backend.
.PARAMETER MinioEndpoint  Browser-facing MinIO URL used in presigned links (MINIO_EXTERNAL_ENDPOINT).
.PARAMETER Demo        Clone mode only: apply compose.demo.yaml (fixed-password demo accounts).
.PARAMETER NoDemo      Do not apply compose.demo.yaml.
.PARAMETER NoRestore   Start a fresh empty stack, restore nothing.
.PARAMETER Overlay     Extra compose file(s).
.PARAMETER Set         Extra env overrides "KEY=VALUE" (repeatable), e.g. -Set MYSQL_PORT=3317.
.PARAMETER MinioImage  Pullable image to retag as the MinIO image (fallback if the build is impossible).
.PARAMETER McImage     Same for mc.
.PARAMETER Yes         Non-interactive: accept overwriting an existing installation's data.
.PARAMETER Passphrase  Ask for the bundle passphrase (or set BUNDLE_PASSPHRASE / -PassphraseFile).
.PARAMETER PassphraseFile  Read the passphrase from the first line of a file.
.PARAMETER KeepExtracted   Keep the unpacked bundle (contains data + secrets) in <Dir>\.bundle.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 .\classroom-bundle-20261007-020203.tar.gz -Dir C:\classroom
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\install.ps1 -Demo
#>
param(
    [Parameter(Position = 0)][string]$Bundle,
    [string]$Dir = ".\classroom",
    [string]$Project,
    [int]$Port = 0,
    [string]$Origin,
    [string]$Bind,
    [string]$MinioEndpoint,
    [switch]$Demo,
    [switch]$NoDemo,
    [switch]$NoRestore,
    [string[]]$Overlay,
    [string[]]$Set,
    [string]$MinioImage,
    [string]$McImage,
    [switch]$Yes,
    [switch]$Passphrase,
    [string]$PassphraseFile,
    [switch]$KeepExtracted
)

$ErrorActionPreference = "Stop"
$SelfDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:StepNo = 0
$script:StepTotal = 11
$Work = $null
$Succeeded = $false

# ------------------------------------------------------------------ output helpers
function Write-StepLine([string]$Text) {
    $script:StepNo++
    Write-Host ""
    Write-Host ("==> [{0}/{1}] {2}" -f $script:StepNo, $script:StepTotal, $Text)
}
function Write-InfoLine([string]$Text) { Write-Host "    $Text" }
function Write-WarnLine([string]$Text) { [Console]::Error.WriteLine("CANH BAO: $Text") }

# ------------------------------------------------------------------ crypto (OpenSSL "enc -pbkdf2" compatible)
if (-not ("ClsBundleCrypt" -as [type])) {
    Add-Type -Language CSharp -TypeDefinition @"
using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

public static class ClsBundleCrypt
{
    // PBKDF2-HMAC-SHA256 producing 48 bytes: AES-256 key (32) + CBC IV (16), exactly what
    // `openssl enc -aes-256-cbc -pbkdf2 -iter N` derives (digest sha256 by default).
    private static void Derive(string pass, byte[] salt, int iter, out byte[] key, out byte[] iv)
    {
        byte[] okm = new byte[48];
        int pos = 0;
        int block = 1;
        using (HMACSHA256 h = new HMACSHA256(Encoding.UTF8.GetBytes(pass)))
        {
            while (pos < 48)
            {
                byte[] msg = new byte[salt.Length + 4];
                Buffer.BlockCopy(salt, 0, msg, 0, salt.Length);
                msg[salt.Length] = (byte)(block >> 24);
                msg[salt.Length + 1] = (byte)(block >> 16);
                msg[salt.Length + 2] = (byte)(block >> 8);
                msg[salt.Length + 3] = (byte)block;
                byte[] u = h.ComputeHash(msg);
                byte[] t = (byte[])u.Clone();
                for (int i = 1; i < iter; i++)
                {
                    u = h.ComputeHash(u);
                    for (int j = 0; j < t.Length; j++) { t[j] ^= u[j]; }
                }
                int n = Math.Min(32, 48 - pos);
                Buffer.BlockCopy(t, 0, okm, pos, n);
                pos += n;
                block++;
            }
        }
        key = new byte[32];
        iv = new byte[16];
        Buffer.BlockCopy(okm, 0, key, 0, 32);
        Buffer.BlockCopy(okm, 32, iv, 0, 16);
    }

    public static void Decrypt(string inPath, string outPath, string pass, int iter)
    {
        using (FileStream fin = File.OpenRead(inPath))
        {
            byte[] header = new byte[16];
            int got = 0;
            while (got < 16)
            {
                int r = fin.Read(header, got, 16 - got);
                if (r <= 0) { break; }
                got += r;
            }
            if (got != 16 || Encoding.ASCII.GetString(header, 0, 8) != "Salted__")
            {
                throw new InvalidDataException("Not an OpenSSL salted file.");
            }
            byte[] salt = new byte[8];
            Buffer.BlockCopy(header, 8, salt, 0, 8);
            byte[] key; byte[] iv;
            Derive(pass, salt, iter, out key, out iv);
            using (Aes aes = Aes.Create())
            {
                aes.KeySize = 256;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = key;
                aes.IV = iv;
                try
                {
                    using (ICryptoTransform dec = aes.CreateDecryptor())
                    using (CryptoStream cs = new CryptoStream(fin, dec, CryptoStreamMode.Read))
                    using (FileStream fout = File.Create(outPath))
                    {
                        cs.CopyTo(fout);
                    }
                }
                catch (CryptographicException)
                {
                    throw new CryptographicException("Wrong passphrase or damaged file.");
                }
            }
        }
    }

    public static void Encrypt(string inPath, string outPath, string pass, int iter)
    {
        byte[] salt = new byte[8];
        using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) { rng.GetBytes(salt); }
        byte[] key; byte[] iv;
        Derive(pass, salt, iter, out key, out iv);
        using (FileStream fin = File.OpenRead(inPath))
        using (FileStream fout = File.Create(outPath))
        {
            byte[] magic = Encoding.ASCII.GetBytes("Salted__");
            fout.Write(magic, 0, 8);
            fout.Write(salt, 0, 8);
            using (Aes aes = Aes.Create())
            {
                aes.KeySize = 256;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = key;
                aes.IV = iv;
                using (ICryptoTransform enc = aes.CreateEncryptor())
                using (CryptoStream cs = new CryptoStream(fout, enc, CryptoStreamMode.Write))
                {
                    fin.CopyTo(cs);
                    cs.FlushFinalBlock();
                }
            }
        }
    }
}
"@
}

# ------------------------------------------------------------------ generic helpers
function Get-PlainFromSecure([System.Security.SecureString]$Secure) {
    $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

# Runs docker quietly (stderr is NOT turned into errors); returns @{ Code; Out }.
function Invoke-DockerQuiet([string[]]$DockerArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & docker @DockerArgs 2>$null
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    return @{ Code = $code; Out = @($out) }
}

# Runs a native command, streams its output and throws on a non-zero exit code.
function Invoke-Native {
    param([string]$Exe, [string[]]$CmdArgs = @())
    & $Exe @CmdArgs | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "'$Exe $($CmdArgs -join ' ')' failed with exit code $LASTEXITCODE" }
}

function Get-ConfValue([string]$File, [string]$Key) {
    if (-not $File -or -not (Test-Path -LiteralPath $File)) { return "" }
    $value = ""
    foreach ($line in (Get-Content -LiteralPath $File)) {
        if ($line.StartsWith($Key + "=")) { $value = $line.Substring($Key.Length + 1).Trim() }
    }
    return $value
}

function Get-EnvValue([string]$Key) { return (Get-ConfValue $script:EnvFile $Key) }

# Replace/append KEY=VALUE in the env file (LF endings, UTF-8 without BOM).
function Set-EnvValue([string]$Key, [string]$Value) {
    $lines = New-Object System.Collections.Generic.List[string]
    $found = $false
    foreach ($line in (Get-Content -LiteralPath $script:EnvFile)) {
        if ($line.StartsWith($Key + "=")) {
            if (-not $found) { $lines.Add($Key + "=" + $Value); $found = $true }
        } else { $lines.Add($line) }
    }
    if (-not $found) { $lines.Add($Key + "=" + $Value) }
    $text = ($lines.ToArray() -join "`n") + "`n"
    [System.IO.File]::WriteAllText($script:EnvFile, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# Only the current user (and SYSTEM/Administrators) may read a secrets file. Best effort.
function Protect-SecretFile([string]$Path) {
    try {
        $user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls.exe $Path /inheritance:r /grant:r ($user + ":(R,W)") "SYSTEM:(F)" "BUILTIN\Administrators:(F)" 2>&1 | Out-Null
    } catch { }
}

function Get-RandomHex([int]$Bytes) {
    $buf = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    return (($buf | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Test-PortBusy([int]$PortNumber) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect("127.0.0.1", $PortNumber, $null, $null)
        if ($async.AsyncWaitHandle.WaitOne(700)) {
            try { $client.EndConnect($async); return $true } catch { return $false }
        }
        return $false
    } finally { $client.Close() }
}

function Get-HostArch {
    $os = (Invoke-DockerQuiet @("info", "--format", "{{.OSType}}")).Out | Select-Object -First 1
    $arch = (Invoke-DockerQuiet @("info", "--format", "{{.Architecture}}")).Out | Select-Object -First 1
    if ($arch -eq "aarch64") { $arch = "arm64" }
    if ($arch -eq "x86_64") { $arch = "amd64" }
    return "$os/$arch"
}

function Remove-DirQuietly([string]$Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return }
    try { Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop }
    catch { & cmd.exe /c rd /s /q ('"' + $Path + '"') 2>&1 | Out-Null }
}

function Confirm-Action([string]$Prompt) {
    if ($Yes) { return $true }
    $answer = Read-Host "$Prompt [y/N]"
    return ($answer -match '^(y|yes)$')
}

# docker compose in <install>\infra with the project's files; output streamed
function Invoke-Compose([string[]]$ComposeArgs) {
    Push-Location $script:Infra
    try {
        $all = @("compose", "-p", $script:Project) + $script:ComposeFiles + @("--env-file", ".env") + $ComposeArgs
        Invoke-Native "docker" $all
    } finally { Pop-Location }
}
function Get-ComposeLines([string[]]$ComposeArgs) {
    Push-Location $script:Infra
    try {
        $all = @("compose", "-p", $script:Project) + $script:ComposeFiles + @("--env-file", ".env") + $ComposeArgs
        $out = & docker @all
        if ($LASTEXITCODE -ne 0) { throw "docker compose $($ComposeArgs -join ' ') failed ($LASTEXITCODE)" }
        if ($null -eq $out) { return @() }
        return @($out)
    } finally { Pop-Location }
}
function Get-ServiceCid([string]$Service) {
    $ids = @(Get-ComposeLines @("ps", "-a", "-q", $Service) | Where-Object { $_ -and $_.Trim() })
    if ($ids.Count -eq 0) { return $null }
    return $ids[0].Trim()
}

function Wait-ServiceHealthy([string]$Service, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $cid = Get-ServiceCid $Service
        if ($cid) {
            $health = (Invoke-DockerQuiet @("inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}", $cid)).Out | Select-Object -First 1
            $state = (Invoke-DockerQuiet @("inspect", "--format", "{{.State.Status}}", $cid)).Out | Select-Object -First 1
            if ($health -eq "healthy") { return $true }
            if ($health -eq "none" -and $state -eq "running") { return $true }
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Invoke-MysqlQuery([string]$Sql) {
    $cid = Get-ServiceCid "mysql"
    # SQL goes through stdin (no quoting problems with Windows PowerShell 5.1 argument passing);
    # the password comes from the container's own environment, never from a command line.
    $out = $Sql | & docker exec -i $cid sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot -N -B $MYSQL_DATABASE' 2>$null
    if ($LASTEXITCODE -ne 0) { return "" }
    return (($out | Select-Object -First 1) -as [string]).Trim()
}

function Invoke-ChildPs([string]$ScriptPath, [string[]]$ScriptArgs) {
    $all = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $ScriptPath) + $ScriptArgs
    & powershell.exe @all | Out-Host
    return $LASTEXITCODE
}

# ================================================================== run
try {
    # ---- mode detection
    $CloneMode = $false
    $ExtractedHere = ((Test-Path -LiteralPath (Join-Path $SelfDir "source.tar.gz")) -and (Test-Path -LiteralPath (Join-Path $SelfDir "data")))
    if (-not $Bundle -and -not $ExtractedHere) {
        $found = @(Get-ChildItem -LiteralPath $SelfDir -Filter "classroom-bundle-*.tar.gz*" -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*.sha256" })
        if ($found.Count -eq 0 -and (Test-Path -LiteralPath (Join-Path $SelfDir "..\compose.yaml"))) {
            $CloneMode = $true
            $NoRestore = $true
        }
    }

    # ================================================================== 1. preflight
    Write-StepLine "Kiem tra moi truong (preflight)"
    foreach ($c in @("docker", "tar.exe")) {
        if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { throw "Thieu lenh '$c'. Cai Docker Desktop / dung Windows 10 1803+ (tar.exe)." }
    }
    if ((Invoke-DockerQuiet @("info")).Code -ne 0) { throw "Docker chua chay. Mo Docker Desktop, doi 'Engine running', roi chay lai." }
    if ((Invoke-DockerQuiet @("compose", "version")).Code -ne 0) { throw "Can Docker Compose v2 ('docker compose')." }
    $mem = [int64]((Invoke-DockerQuiet @("info", "--format", "{{.MemTotal}}")).Out | Select-Object -First 1)
    if ($mem -lt 5700000000) {
        throw ("Docker chi co {0} MB RAM; can >= 6 GB. Docker Desktop (WSL2): tao %UserProfile%\.wslconfig voi [wsl2] memory=8GB, chay 'wsl --shutdown', mo lai Docker Desktop." -f [int]($mem / 1MB))
    }
    $hostArch = Get-HostArch
    Write-InfoLine ("Docker OK, RAM cho Docker {0} GB, {1}" -f [int]($mem / 1GB), $hostArch)
    if ($CloneMode) {
        $DirAbs = [System.IO.Path]::GetFullPath((Join-Path $SelfDir "..\.."))
        if ($PSBoundParameters.ContainsKey("Dir")) { Write-WarnLine "Che do clone cai ngay trong repo ($DirAbs); bo qua -Dir." }
    } else {
        New-Item -ItemType Directory -Force -Path $Dir | Out-Null
        $DirAbs = (Resolve-Path -LiteralPath $Dir).ProviderPath.TrimEnd([char]92)
    }
    $drive = New-Object System.IO.DriveInfo ($DirAbs.Substring(0, 1))
    if ($drive.AvailableFreeSpace -lt 10GB) { throw ("Can >= 10 GB trong o {0} (con {1} GB)." -f $drive.Name, [int]($drive.AvailableFreeSpace / 1GB)) }
    Write-InfoLine ("Dia trong: {0} GB tai {1}" -f [int]($drive.AvailableFreeSpace / 1GB), $DirAbs)
    if ($DirAbs.Length -gt 60) { Write-WarnLine "Duong dan cai dat dai ($($DirAbs.Length) ky tu): nen dung duong dan ngan nhu C:\classroom de tranh gioi han 260 ky tu cua Windows." }

    # ================================================================== 2. bundle
    Write-StepLine "Tim va kiem tra bundle"
    $BundleDir = $null
    $Conf = $null
    $BundleDemo = ""
    $UseDemo = $false
    if ($CloneMode) {
        Write-InfoLine "Khong co bundle: che do CLONE (stack moi, rong) tai $DirAbs"
        if ($Demo) { $UseDemo = $true; if (-not $Project) { $Project = "classroom-demo" } }
        else { if (-not $Project) { $Project = "online-classroom" } }
    } else {
        if ($Bundle -or -not $ExtractedHere) {
            if (-not $Bundle) {
                $found = @(Get-ChildItem -LiteralPath $SelfDir -Filter "classroom-bundle-*.tar.gz*" -File | Where-Object { $_.Name -notlike "*.sha256" } | Sort-Object LastWriteTime -Descending)
                if ($found.Count -eq 0) { throw "Khong thay classroom-bundle-*.tar.gz[.enc] canh install.ps1. Truyen duong dan bundle." }
                $Bundle = $found[0].FullName
            }
            if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { throw "Khong thay file bundle: $Bundle" }
            $Bundle = (Resolve-Path -LiteralPath $Bundle).ProviderPath
            Write-InfoLine ("Bundle: {0} ({1} MB)" -f $Bundle, [int]((Get-Item -LiteralPath $Bundle).Length / 1MB))
            $shaFile = $Bundle -replace '\.tar\.gz.*$', '.sha256'
            if (Test-Path -LiteralPath $shaFile) {
                $want = ((Get-Content -LiteralPath $shaFile -TotalCount 1) -split '\s+')[0].ToLowerInvariant()
                $got = (Get-FileHash -Algorithm SHA256 -LiteralPath $Bundle).Hash.ToLowerInvariant()
                if ($want -ne $got) { throw "SHA-256 cua bundle KHONG khop (file hong hoac bi sua khi chuyen). Chep lai file." }
                Write-InfoLine ("SHA-256 bundle khop: {0}..." -f $got.Substring(0, 16))
            } else { Write-WarnLine "Khong co file .sha256 canh bundle - bo qua kiem tra checksum ngoai." }

            $Work = Join-Path $DirAbs ".bundle"
            Remove-DirQuietly $Work
            New-Item -ItemType Directory -Force -Path $Work | Out-Null
            $tarFile = $Bundle
            $header = New-Object byte[] 8
            $fs = [System.IO.File]::OpenRead($Bundle)
            try { [void]$fs.Read($header, 0, 8) } finally { $fs.Close() }
            if ([System.Text.Encoding]::ASCII.GetString($header) -eq "Salted__") {
                $pass = $env:BUNDLE_PASSPHRASE
                if ($PassphraseFile) { $pass = (Get-Content -LiteralPath $PassphraseFile -TotalCount 1) }
                if (-not $pass) {
                    $pass = Get-PlainFromSecure (Read-Host "Passphrase cua bundle" -AsSecureString)
                }
                Write-InfoLine "Giai ma bundle (AES-256, PBKDF2-SHA256 200000 vong)..."
                $tarFile = Join-Path $Work "bundle.tar.gz"
                try { [ClsBundleCrypt]::Decrypt($Bundle, $tarFile, $pass, 200000) }
                catch { throw "Giai ma that bai: sai passphrase hoac file hong." }
            } elseif ($Passphrase) { Write-WarnLine "-Passphrase duoc dua nhung bundle khong ma hoa." }
            Write-InfoLine "Giai nen bundle..."
            Invoke-Native "tar.exe" @("-xzf", $tarFile, "-C", $Work)
            if ($tarFile -ne $Bundle) { Remove-Item -LiteralPath $tarFile -Force }
            $sub = @(Get-ChildItem -LiteralPath $Work -Directory -Filter "classroom-bundle-*")
            if ($sub.Count -eq 0) { throw "Bundle khong dung dinh dang (khong co thu muc classroom-bundle-*)." }
            $BundleDir = $sub[0].FullName
        } else {
            $BundleDir = $SelfDir
            Write-InfoLine "Dung bundle da giai nen: $BundleDir"
        }
        foreach ($f in @("bundle.conf", "source.tar.gz", "env.bundle")) {
            if (-not (Test-Path -LiteralPath (Join-Path $BundleDir $f))) { throw "Bundle thieu file $f." }
        }
        foreach ($line in (Get-Content -LiteralPath (Join-Path $BundleDir "SHA256SUMS"))) {
            if (-not $line.Trim()) { continue }
            $parts = $line -split '\s+', 2
            $target = Join-Path $BundleDir $parts[1].Trim()
            if (-not (Test-Path -LiteralPath $target)) { throw "Bundle thieu $($parts[1])" }
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant() -ne $parts[0].ToLowerInvariant()) {
                throw "Noi dung bundle khong khop SHA256SUMS ($($parts[1]))."
            }
        }
        Write-InfoLine "Cac file ben trong bundle khop SHA256SUMS."
        $Conf = Join-Path $BundleDir "bundle.conf"
        $bundleProject = Get-ConfValue $Conf "PROJECT"
        $BundleDemo = Get-ConfValue $Conf "DEMO"
        if (-not $Project) { $Project = $bundleProject }
        if (-not $Project) { $Project = "classroom-demo" }
        $commit = Get-ConfValue $Conf "GIT_COMMIT"
        Write-InfoLine ("Commit {0}, Flyway V{1}, project '{2}'" -f $commit.Substring(0, [Math]::Min(10, $commit.Length)), (Get-ConfValue $Conf "FLYWAY_VERSION"), $Project)
        $UseDemo = ($BundleDemo -eq "1") -and (-not $NoDemo)
    }
    if ($Project -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw "Ten project khong hop le: $Project" }

    # ================================================================== 3. existing install?
    Write-StepLine "Phat hien cai dat hien co"
    $existingVolumes = @((Invoke-DockerQuiet @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$Project")).Out | Where-Object { $_ }).Count
    $runningOwn = @((Invoke-DockerQuiet @("ps", "-q", "--filter", "label=com.docker.compose.project=$Project")).Out | Where-Object { $_ }).Count
    if ($existingVolumes -gt 0) {
        Write-WarnLine "Project '$Project' da co $existingVolumes volume du lieu tren may nay."
        if ($NoRestore) {
            Write-InfoLine "Se chi build/khoi dong lai, KHONG dung vao du lieu."
        } else {
            Write-Host "    Khoi phuc se GHI DE du lieu hien co (MySQL/MongoDB/Neo4j/MinIO) bang du lieu trong bundle."
            if (-not $Yes) {
                $typed = Read-Host "    Go lai ten project '$Project' de xac nhan ghi de"
                if ($typed -ne $Project) { throw "Khong khop - khong thay doi gi." }
            }
            Write-InfoLine "Se tao ban sao luu an toan truoc khi ghi de."
        }
    } else { Write-InfoLine "Chua co du lieu cho project '$Project' - cai dat moi." }

    # ================================================================== 4. source + env
    if ($CloneMode) { Write-StepLine "Dung ma nguon san co trong $DirAbs" } else { Write-StepLine "Giai nen ma nguon vao $DirAbs" }
    if (-not $CloneMode) { Invoke-Native "tar.exe" @("-xzf", (Join-Path $BundleDir "source.tar.gz"), "-C", $DirAbs) }
    $Infra = Join-Path $DirAbs "infra"
    if (-not (Test-Path -LiteralPath (Join-Path $Infra "compose.yaml"))) { throw "Ma nguon khong co infra\compose.yaml" }
    $EnvFile = Join-Path $Infra ".env"
    if ($CloneMode) {
        if (Test-Path -LiteralPath $EnvFile) { Write-InfoLine "Giu nguyen $EnvFile co san (khong sinh lai bi mat)." }
        else {
            $example = Join-Path $DirAbs ".env.example"
            if (-not (Test-Path -LiteralPath $example)) { throw "Khong thay $example" }
            Copy-Item -LiteralPath $example -Destination $EnvFile
            foreach ($k in @("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "MONGO_INITDB_ROOT_PASSWORD", "NEO4J_PASSWORD", "MINIO_ROOT_PASSWORD")) { Set-EnvValue $k (Get-RandomHex 16) }
            foreach ($k in @("JWT_SECRET", "MOCK_PAYMENT_WEBHOOK_SECRET")) { Set-EnvValue $k (Get-RandomHex 32) }
            Write-InfoLine "Da tao $EnvFile tu .env.example voi bi mat NGAU NHIEN."
        }
    } elseif (Test-Path -LiteralPath $EnvFile) {
        Write-InfoLine "Giu nguyen $EnvFile co san (khong ghi de bi mat). Bundle env luu o $Infra\.env.from-bundle."
        Copy-Item -LiteralPath (Join-Path $BundleDir "env.bundle") -Destination (Join-Path $Infra ".env.from-bundle") -Force
        Protect-SecretFile (Join-Path $Infra ".env.from-bundle")
    } else {
        Copy-Item -LiteralPath (Join-Path $BundleDir "env.bundle") -Destination $EnvFile
        Write-InfoLine "Da dat $EnvFile tu env.bundle (chua bi mat that)."
    }
    Protect-SecretFile $EnvFile

    if ($Port -gt 0) { Set-EnvValue "FRONTEND_PORT" ([string]$Port) }
    $FrontPort = Get-EnvValue "FRONTEND_PORT"; if (-not $FrontPort) { $FrontPort = "3000" }
    if ($Bind) { Set-EnvValue "HOST_BIND_ADDRESS" $Bind }
    $cors = ""
    if ($Origin) {
        $Origin = $Origin.TrimEnd("/")
        if ($Origin -notmatch '^https?://') { throw "-Origin phai bat dau bang http:// hoac https://" }
        $cors = "$Origin,http://localhost:$FrontPort,http://127.0.0.1:$FrontPort"
        if ($Origin.StartsWith("https://")) { Set-EnvValue "APP_COOKIE_SECURE" "true" }
    } elseif ($Port -gt 0) { $cors = "http://localhost:$FrontPort,http://127.0.0.1:$FrontPort" }
    if ($cors) { Set-EnvValue "APP_CORS_ALLOWED_ORIGINS" $cors }
    if ($MinioEndpoint) { Set-EnvValue "MINIO_EXTERNAL_ENDPOINT" $MinioEndpoint.TrimEnd("/") }
    foreach ($kv in @($Set | Where-Object { $_ })) {
        if ($kv -notmatch '^[A-Za-z_][A-Za-z0-9_]*=') { throw "-Set can KEY=VALUE, nhan: $kv" }
        $idx = $kv.IndexOf("=")
        Set-EnvValue $kv.Substring(0, $idx) $kv.Substring($idx + 1)
    }

    $ComposeFiles = @("-f", "compose.yaml")
    if ($UseDemo) { $ComposeFiles += @("-f", "compose.demo.yaml") }
    $overlayAbs = @()
    foreach ($o in @($Overlay | Where-Object { $_ })) {
        if (-not (Test-Path -LiteralPath $o)) { throw "Overlay khong ton tai: $o" }
        $full = (Resolve-Path -LiteralPath $o).ProviderPath
        $ComposeFiles += @("-f", $full); $overlayAbs += $full
    }
    $conf = @("# written by install.ps1 - read by infra\scripts\server-up.ps1 / server-up.sh (no secrets here)", "PROJECT=$Project")
    if ($UseDemo) { $conf += "DEMO=1" } else { $conf += "DEMO=0" }
    $conf += ("OVERLAYS=" + ($overlayAbs -join " "))
    [System.IO.File]::WriteAllText((Join-Path $Infra ".server.conf"), (($conf -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

    $null = Get-ComposeLines @("config", "-q")
    # container_name is fixed in compose.yaml: containers of ANOTHER project with that name collide.
    foreach ($line in (Get-ComposeLines @("config"))) {
        if ($line -match '^\s*container_name:\s*(\S+)\s*$') {
            $n = $Matches[1]
            $r = Invoke-DockerQuiet @("inspect", "--format", "{{.Config.Labels}}", $n)
            if ($r.Code -eq 0) {
                $owner = ""
                if ((([string]($r.Out -join " ")) -match 'com\.docker\.compose\.project:([^\s\]]+)')) { $owner = $Matches[1] }
                if ($owner -and $owner -ne $Project) {
                    throw "Container '$n' (con ton tai, co the dang dung) thuoc project '$owner'; ten container co dinh nen se trung. Dung -Project $owner de nang cap tai cho, hoac xoa/doi ten stack do."
                }
            }
        }
    }
    $BackendPort = Get-EnvValue "BACKEND_PORT"; if (-not $BackendPort) { $BackendPort = "8080" }
    $BindAddr = Get-EnvValue "HOST_BIND_ADDRESS"; if (-not $BindAddr) { $BindAddr = "127.0.0.1" }
    Write-InfoLine "project=$Project  frontend=:$FrontPort  backend=:$BackendPort  bind=$BindAddr  demo=$UseDemo"

    if ($runningOwn -eq 0) {
        $defaults = @{ FRONTEND_PORT = 3000; BACKEND_PORT = 8080; MYSQL_PORT = 3307; MONGO_PORT = 27017; NEO4J_HTTP_PORT = 7474; NEO4J_BOLT_PORT = 7687; MINIO_PORT = 9000; MINIO_CONSOLE_PORT = 9001 }
        foreach ($name in ($defaults.Keys | Sort-Object)) {
            $p = Get-EnvValue $name
            if ($p) { $p = [int]$p } else { $p = $defaults[$name] }
            if (Test-PortBusy $p) { throw "Cong $p ($name) dang bi chuong trinh khac dung. Doi bang -Set $name=<cong khac> (hoac -Port cho frontend). Xem: netstat -ano | findstr :$p" }
        }
        Write-InfoLine "Cac cong can dung deu dang trong."
    }

    # ================================================================== 5. verify backup
    Write-StepLine "Kiem tra du lieu sao luu (backup.json: size + sha256 tung thanh phan)"
    $RestoreData = (-not $NoRestore)
    if ($CloneMode) { Write-InfoLine "Che do clone: khong co du lieu de kiem tra." }
    elseif ($RestoreData) {
        $rc = Invoke-ChildPs (Join-Path $Infra "scripts\restore.ps1") @("-BackupDir", (Join-Path $BundleDir "data"), "-VerifyOnly")
        if ($rc -ne 0) { throw "Backup trong bundle khong hop le (restore.ps1 -VerifyOnly that bai)." }
    } else { Write-InfoLine "-NoRestore: bo qua." }

    # ================================================================== 6. images
    Write-StepLine "Image MinIO/mc (tu build; chi nap tu bundle neu bundle cu)"
    $composeText = Get-Content -LiteralPath (Join-Path $Infra "compose.yaml")
    foreach ($pair in @(@("minio", $MinioImage), @("mc", $McImage))) {
        if (-not $pair[1]) { continue }
        $target = $null
        foreach ($l in $composeText) { if ($l -match ('^\s*image:\s*(classroom/' + $pair[0] + ':\S+)')) { $target = $Matches[1]; break } }
        if (-not $target) { throw "Khong thay image classroom/$($pair[0]) trong compose.yaml" }
        Invoke-Native "docker" @("pull", $pair[1])
        Invoke-Native "docker" @("tag", $pair[1], $target)
        Write-InfoLine "dung $($pair[1]) lam $target"
    }
    if (-not $CloneMode -and (Test-Path -LiteralPath (Join-Path $BundleDir "images.list"))) {
        $legacyTar = Join-Path $BundleDir "images.tar.gz"
        $needLoad = $false
        foreach ($img in (Get-Content -LiteralPath (Join-Path $BundleDir "images.list"))) {
            if (-not $img.Trim()) { continue }
            $r = Invoke-DockerQuiet @("image", "inspect", "--format", "{{.Os}}/{{.Architecture}}", $img.Trim())
            if ($r.Code -ne 0 -or ([string]($r.Out | Select-Object -First 1)) -ne $hostArch) { $needLoad = $true }
        }
        if ($needLoad -and (Test-Path -LiteralPath $legacyTar)) {
            Write-InfoLine "bundle cu: docker load images.tar.gz ..."
            Invoke-Native "docker" @("load", "-i", $legacyTar)
        }
    } else {
        Write-InfoLine "MinIO/mc se duoc build tu infra\minio o buoc tiep theo (vai phut lan dau; can Docker Hub + proxy.golang.org)."
    }

    # ================================================================== 7. data services
    Write-StepLine "Khoi dong dich vu du lieu (mysql, mongodb, neo4j, minio, minio-init; build MinIO neu chua co)"
    Invoke-Compose @("up", "-d", "mysql", "mongodb", "neo4j", "minio", "minio-init")
    foreach ($s in @("mysql", "mongodb", "neo4j", "minio")) {
        Write-Host -NoNewline "    cho $s healthy ... "
        if (-not (Wait-ServiceHealthy $s 240)) { Write-Host ""; throw "$s khong healthy sau 240 s: docker compose -p $Project logs $s" }
        Write-Host "OK"
    }
    $initCid = Get-ServiceCid "minio-init"
    for ($i = 0; $i -lt 30; $i++) {
        $st = [string]((Invoke-DockerQuiet @("inspect", "--format", "{{.State.Status}}", $initCid)).Out | Select-Object -First 1)
        if ($st -eq "exited") { break }
        Start-Sleep -Seconds 2
    }
    $ec = [string]((Invoke-DockerQuiet @("inspect", "--format", "{{.State.ExitCode}}", $initCid)).Out | Select-Object -First 1)
    if ($ec -ne "0") { throw "minio-init loi (tao bucket): docker logs classroom-minio-init" }
    Write-InfoLine "minio-init xong (bucket san sang)."

    # ================================================================== 8. restore
    Write-StepLine "Khoi phuc du lieu (truoc khi chay backend)"
    if ($RestoreData) {
        $demoOverlay = @()
        if ($UseDemo) { $demoOverlay = @("-OverlayFile", (Join-Path $Infra "compose.demo.yaml")) }
        if ($existingVolumes -gt 0 -and (Get-ServiceCid "mysql")) {
            $safety = Join-Path $DirAbs "backups\pre-install"
            Write-InfoLine "Ban sao luu an toan du lieu hien co -> $safety"
            $rc = Invoke-ChildPs (Join-Path $Infra "scripts\backup.ps1") (@("-OutDir", $safety, "-ProjectName", $Project, "-InfraDir", $Infra) + $demoOverlay)
            if ($rc -eq 0) { Write-InfoLine "da luu (MySQL/MongoDB/MinIO)." } else { Write-WarnLine "Khong tao duoc ban sao luu an toan (co the DB hien co rong)." }
        }
        $rArgs = @("-BackupDir", (Join-Path $BundleDir "data"), "-ProjectName", $Project, "-Force", "-ConfirmProject", $Project, "-InfraDir", $Infra, "-MirrorRemove") + $demoOverlay
        foreach ($o in $overlayAbs) { $rArgs += @("-OverlayFile", $o) }
        if (Test-Path -LiteralPath (Join-Path $BundleDir "data\neo4j.dump")) { $rArgs += "-IncludeNeo4j" }
        $rc = Invoke-ChildPs (Join-Path $Infra "scripts\restore.ps1") $rArgs
        if ($rc -ne 0) { throw "restore.ps1 that bai (ma $rc). Backend duoc de DUNG; du lieu co the khoi phuc do dang." }
        Write-Host "    Khoi phuc xong."
    } else { Write-InfoLine "Khong khoi phuc: stack trong." }

    # ================================================================== 9. backend + frontend
    Write-StepLine "Build + khoi dong backend, frontend (build Maven/npm lan dau mat vai phut)"
    Invoke-Compose @("up", "-d", "--build", "backend", "frontend")
    Write-Host -NoNewline "    cho backend /api/v1/health/readiness ... "
    $ok = $false
    for ($i = 0; $i -lt 120; $i++) {
        try {
            $resp = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$BackendPort/api/v1/health/readiness"
            if ($resp.Content -match '"status"\s*:\s*"UP"') { $ok = $true; break }
        } catch { }
        Start-Sleep -Seconds 3
    }
    if (-not $ok) { Write-Host ""; throw "Backend khong UP: docker compose -p $Project logs --tail 100 backend" }
    Write-Host "UP"

    # ================================================================== 10. smoke
    Write-StepLine "Kiem tra sau cai dat (smoke test)"
    $code = 0
    for ($i = 0; $i -lt 30; $i++) {
        try { $code = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$FrontPort/").StatusCode } catch { $code = 0 }
        if ($code -eq 200) { break }
        Start-Sleep -Seconds 2
    }
    if ($code -ne 200) { throw "Frontend http://127.0.0.1:$FrontPort/ tra ve $code" }
    Write-InfoLine "Frontend 200 qua cong $FrontPort"
    try { $code = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri "http://127.0.0.1:$FrontPort/api/v1/health/readiness").StatusCode } catch { $code = 0 }
    if ($code -ne 200) { throw "Proxy /api qua frontend tra ve $code" }
    Write-InfoLine "Proxy /api qua frontend OK"
    if ($RestoreData) {
        $fw = Invoke-MysqlQuery "select max(cast(version as unsigned)) from flyway_schema_history where success=1"
        $wantFw = Get-ConfValue $Conf "FLYWAY_VERSION"
        if ($fw -ne $wantFw) { throw "Flyway V$fw khac bundle V$wantFw" }
        Write-InfoLine "Flyway V$fw (khop bundle)"
        $mismatch = $false
        foreach ($line in (Get-Content -LiteralPath $Conf)) {
            if ($line -notmatch '^COUNT_([A-Za-z0-9_]+)=(.*)$') { continue }
            $t = $Matches[1]; $want = $Matches[2].Trim()
            $got = Invoke-MysqlQuery "select count(*) from $t"
            if ($got -eq $want) { Write-InfoLine "bang ${t}: $got dong (khop)" }
            else { Write-WarnLine "bang ${t}: $got dong, bundle co $want"; $mismatch = $true }
        }
        if ($mismatch) { throw "So dong khong khop sau khoi phuc." }
    }
    $Api = "http://127.0.0.1:$BackendPort/api/v1"
    if ($UseDemo -and $RestoreData) {
        $tok = $null
        try {
            $login = Invoke-RestMethod -Method Post -Uri "$Api/auth/login" -ContentType "application/json" -Body '{"email":"owner@classroom.local","password":"Password123!"}'
            $tok = $login.data.token
        } catch { }
        if ($tok) {
            Write-InfoLine "Dang nhap owner@classroom.local OK"
            $hdr = @{ Authorization = "Bearer $tok" }
            try {
                $cls = Invoke-RestMethod -Uri "$Api/classes/slug/lop-demo-day-du" -Headers $hdr
                $cid = $cls.data.id
                Write-InfoLine "Lop trung bay lop-demo-day-du: OK (id $cid)"
                foreach ($p in @("leaderboard", "blog-posts", "courses")) {
                    try { $c = (Invoke-WebRequest -UseBasicParsing -Uri "$Api/classes/$cid/$p" -Headers $hdr).StatusCode } catch { $c = 0 }
                    Write-InfoLine "  /classes/{id}/$p -> HTTP $c"
                }
            } catch { Write-WarnLine "Khong thay lop lop-demo-day-du (co the khong co trong du lieu nay)." }
        } else { Write-WarnLine "Khong dang nhap duoc owner@classroom.local (neu du lieu khong phai demo thi binh thuong)." }
    }

    # ================================================================== 11. summary
    Write-StepLine "Hoan tat"
    $minioConsole = Get-EnvValue "MINIO_CONSOLE_PORT"
    Write-Host ""
    Write-Host "  ====================================================================="
    Write-Host "   CAI DAT XONG - project '$Project' trong $DirAbs"
    Write-Host "  ====================================================================="
    Write-Host "   Web      : http://localhost:$FrontPort"
    Write-Host "   API      : http://127.0.0.1:${BackendPort}/api/v1/health/readiness"
    Write-Host "   MinIO    : console http://127.0.0.1:$minioConsole  (tai khoan/mat khau trong $EnvFile)"
    Write-Host "   Bind     : $BindAddr"
    if ($BindAddr -eq "127.0.0.1") { Write-Host "              (chi truy cap duoc tu chinh server; mo ra ngoai: -Bind 0.0.0.0 + Windows Firewall inbound rule cho cong $FrontPort, hoac reverse proxy HTTPS)" }
    else { Write-Host "              Mo Windows Firewall (inbound TCP $FrontPort) chi cho mang tin cay. Cong 80/443/3000 co the dang bi IIS/Skype/dich vu khac chiem." }
    Write-Host "   Bi mat   : $EnvFile - KHONG commit/chia se."
    Write-Host ""
    Write-Host "   Dieu khien: powershell -NoProfile -ExecutionPolicy Bypass -File `"$Infra\scripts\server-up.ps1`" status|logs|stop|start|restart|backup|update"
    Write-Host "   Tao bundle moi (server nguon): infra\scripts\make-migration-bundle.ps1 [-Encrypt]"
    if ($UseDemo) {
        Write-Host ""
        Write-Host "  !!! CANH BAO DEMO !!!"
        Write-Host "   Stack dang dung compose.demo.yaml: tai khoan co dinh (owner@classroom.local, staff@, student.*@classroom.local,"
        Write-Host "   demo.hocvien*@example.com) co mat khau  Password123!  va thanh toan SANDBOX. TUYET DOI khong mo ra Internet."
    } elseif ($RestoreData -and $BundleDemo -eq "1") {
        Write-Host ""
        Write-Host "  Luu y: -NoDemo da tat compose.demo.yaml, nhung DU LIEU van chua tai khoan demo (mat khau Password123!)."
        Write-Host "  Doi mat khau / vo hieu hoa truoc khi mo ra ngoai (infra\scripts\secure-server-accounts.ps1, docs\RUNBOOK.md)."
    }
    $Succeeded = $true
} catch {
    [Console]::Error.WriteLine("")
    [Console]::Error.WriteLine("LOI: " + $_.Exception.Message)
    [Console]::Error.WriteLine("CAI DAT THAT BAI. Du lieu cu (neu co) khong bi xoa; volume Docker khong bi xoa.")
    if ($Project) { [Console]::Error.WriteLine("Xem log: docker compose -p $Project logs --tail 100   |   chay lai install.ps1 sau khi sua loi (an toan).") }
} finally {
    if ($Work -and -not $KeepExtracted) { Remove-DirQuietly $Work }
}
if ($Succeeded) { exit 0 } else { exit 1 }
