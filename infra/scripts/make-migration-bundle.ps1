<#
.SYNOPSIS
  Run on the OLD server (Windows): build ONE self-contained migration bundle (source code + fresh
  data backup + env). PowerShell twin of make-migration-bundle.sh; the bundle format is identical, so
  install.sh (Linux/macOS) and install.ps1 (Windows) can both install it.

.DESCRIPTION
  Output (default %USERPROFILE%\Classroom-migration\):
    classroom-bundle-<yyyyMMdd-HHmmss>.tar.gz[.enc]   the bundle
    classroom-bundle-<...>.sha256                      checksum of that file
    install.sh, install.ps1, README-MIGRATION.md       copies for use before the bundle is opened
  Windows PowerShell 5.1 compatible, pure ASCII. Needs tar.exe (Windows 10 1803+), git, docker.

  THE BUNDLE CONTAINS SECRETS (env.bundle) AND PERSONAL DATA. Copy it over a trusted channel, keep it
  private, delete it after the migration - or use -Encrypt (AES-256-CBC, PBKDF2-SHA256 200000
  iterations; the same format as `openssl enc -aes-256-cbc -pbkdf2 -iter 200000`).

.PARAMETER Project    Compose project holding the data (default classroom-demo).
.PARAMETER OverlayFile  Compose overlay(s) of that project (default compose.demo.yaml for classroom-demo).
.PARAMETER OutDir     Where to write the bundle.
.PARAMETER Encrypt    Encrypt the tar (passphrase from BUNDLE_PASSPHRASE or a prompt).
.PARAMETER HeadOnly   Source = `git archive HEAD` only (default: HEAD + local changes + untracked files).
.PARAMETER Strict     Refuse a dirty git working tree.
.PARAMETER NoNeo4j    Skip the Neo4j dump (the default includes it; neo4j stops for ~20 s).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\make-migration-bundle.ps1 -Encrypt
#>
param(
    [string]$Project = "classroom-demo",
    [string[]]$OverlayFile,
    [string]$OutDir = (Join-Path $env:USERPROFILE "Classroom-migration"),
    [switch]$Encrypt,
    [switch]$HeadOnly,
    [switch]$Strict,
    [switch]$NoNeo4j
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")
$RepoDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).ProviderPath
$InfraDir = Join-Path $RepoDir "infra"
$Staging = $null

# ------------------------------------------------------------------ crypto (same as install.ps1)
if (-not ("ClsBundleCrypt" -as [type])) {
    Add-Type -Language CSharp -TypeDefinition @"
using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

public static class ClsBundleCrypt
{
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

function Get-PlainFromSecure([System.Security.SecureString]$Secure) {
    $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
function Write-LfFile([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, ($Text -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
}
function Invoke-GitQuiet([string[]]$GitArgs) {
    $out = & git -C $RepoDir @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') failed ($LASTEXITCODE)" }
    if ($null -eq $out) { return @() }
    return @($out)
}
function Remove-DirQuietly([string]$Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return }
    try { Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop }
    catch { & cmd.exe /c rd /s /q ('"' + $Path + '"') 2>&1 | Out-Null }
}

try {
    if ($env:BUNDLE_PASSPHRASE) { $Encrypt = $true }
    if (-not $OverlayFile -and $Project -eq "classroom-demo") { $OverlayFile = @((Join-Path $InfraDir "compose.demo.yaml")) }
    $OverlayFile = @($OverlayFile | Where-Object { $_ } | ForEach-Object { Get-AbsolutePath $_ })
    $demoFlag = 0
    foreach ($o in $OverlayFile) { if ($o -like "*compose.demo.yaml") { $demoFlag = 1 } }

    # ---------------------------------------------------------------- 1. preflight
    Write-Step "[1/8] Kiem tra moi truong (preflight)"
    foreach ($c in @("docker", "git", "tar.exe")) { if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { throw "Thieu lenh '$c'." } }
    if (-not (Test-Path -LiteralPath (Join-Path $InfraDir ".env"))) { throw "Khong thay infra\.env - day la file chua bi mat can dua sang server moi." }
    $running = @(Get-NativeOutput "docker" @("ps", "-q", "--filter", "label=com.docker.compose.project=$Project")).Count
    if ($running -lt 4) { throw "Project '$Project' chua chay du cac container (thay $running). Khoi dong stack truoc (-Project NAME neu ten khac)." }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $OutDir = (Resolve-Path -LiteralPath $OutDir).ProviderPath.TrimEnd([char]92)
    $drive = New-Object System.IO.DriveInfo ($OutDir.Substring(0, 1))
    if ($drive.AvailableFreeSpace -lt 12GB) { throw ("Can it nhat 12 GB trong {0} (con {1} GB)." -f $OutDir, [int]($drive.AvailableFreeSpace / 1GB)) }
    Write-Info "docker OK, project $Project dang chay ($running container), con $([int]($drive.AvailableFreeSpace / 1GB)) GB"
    $dirty = $false
    if ((Invoke-GitQuiet @("status", "--porcelain")).Count -gt 0) {
        $dirty = $true
        if ($Strict) { throw "Working tree chua sach (-Strict). Commit truoc hoac bo -Strict." }
        Write-Warning "Working tree chua sach. Se dong goi: HEAD + thay doi cuc bo + file moi chua ignore (ghi vao MANIFEST)."
    }
    $commit = (Invoke-GitQuiet @("rev-parse", "HEAD"))[0].Trim()
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $name = "classroom-bundle-$stamp"
    $Staging = Join-Path $OutDir (".staging-" + $stamp)
    $B = Join-Path $Staging $name
    New-Item -ItemType Directory -Force -Path $B | Out-Null

    # ---------------------------------------------------------------- 2. source
    Write-Step "[2/8] Dong goi ma nguon ($commit)"
    $srcTar = Join-Path $B "source.tar.gz"
    if ($HeadOnly) {
        Invoke-Native "git" @("-C", $RepoDir, "archive", "--format=tar.gz", "-o", $srcTar, "HEAD")
        $sourceMode = "git archive HEAD"
    } else {
        $listFile = Join-Path $Staging "files.list"
        $names = New-Object System.Collections.Generic.List[string]
        foreach ($f in (Invoke-GitQuiet @("-c", "core.quotepath=off", "ls-files", "--cached", "--others", "--exclude-standard"))) {
            if (-not $f) { continue }
            if (Test-Path -LiteralPath (Join-Path $RepoDir $f)) { $names.Add($f) }
        }
        Write-LfFile $listFile (($names.ToArray() -join "`n") + "`n")
        Invoke-Native "tar.exe" @("-czf", $srcTar, "-C", $RepoDir, "-T", $listFile)
        $sourceMode = "working tree (tracked + untracked, minus .gitignore)"
    }
    $srcList = @(& tar.exe -tzf $srcTar)
    if (-not ($srcList -contains "infra/compose.yaml")) { throw "source.tar.gz khong chua infra/compose.yaml" }
    if (@($srcList | Where-Object { $_ -match '(^|/)\.env$' }).Count -gt 0) { throw "source.tar.gz chua file .env - tu choi." }
    Write-Info ("-> source.tar.gz ({0} bytes, {1})" -f (Get-Item -LiteralPath $srcTar).Length, $sourceMode)

    # ---------------------------------------------------------------- 3. data backup
    Write-Step "[3/8] Sao luu du lieu moi (backup.ps1: MySQL + MongoDB + Neo4j + MinIO)"
    $bk = Join-Path $Staging "backup"
    $bArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $PSScriptRoot "backup.ps1"), "-OutDir", $bk, "-ProjectName", $Project)
    foreach ($o in $OverlayFile) { $bArgs += @("-OverlayFile", $o) }
    if (-not $NoNeo4j) { $bArgs += "-IncludeNeo4jDump" }
    & powershell.exe @bArgs | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "backup.ps1 that bai (ma $LASTEXITCODE)." }
    $bkDir = @(Get-ChildItem -LiteralPath $bk -Directory)[0]
    if (-not $bkDir -or -not (Test-Path -LiteralPath (Join-Path $bkDir.FullName "backup.json"))) { throw "backup.ps1 khong tao backup.json" }
    Move-Item -LiteralPath $bkDir.FullName -Destination (Join-Path $B "data")
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "restore.ps1") -BackupDir (Join-Path $B "data") -VerifyOnly | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Verify backup that bai." }
    Write-Info "backup.json da duoc kiem tra lai (sha256 tung thanh phan)."

    $mysqlCid = (Get-NativeOutput "docker" @("ps", "-q", "--filter", "label=com.docker.compose.project=$Project", "--filter", "label=com.docker.compose.service=mysql"))[0].Trim()
    function Get-MysqlValue([string]$Sql) {
        $r = $Sql | & docker exec -i $mysqlCid sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot -N -B $MYSQL_DATABASE' 2>$null
        if ($LASTEXITCODE -ne 0) { return "?" }
        return (($r | Select-Object -First 1) -as [string]).Trim()
    }
    $flyway = Get-MysqlValue "select max(cast(version as unsigned)) from flyway_schema_history where success=1"
    $counts = ""
    foreach ($t in @("users", "classrooms", "class_members", "courses", "lessons", "posts", "blog_posts", "media_assets", "exams", "orders")) {
        $counts += "COUNT_$t=$(Get-MysqlValue "select count(*) from $t")`n"
    }

    # ---------------------------------------------------------------- 4. env
    Write-Step "[4/8] Env file"
    Copy-Item -LiteralPath (Join-Path $InfraDir ".env") -Destination (Join-Path $B "env.bundle")
    try {
        $user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls.exe (Join-Path $B "env.bundle") /inheritance:r /grant:r ($user + ":(R,W)") 2>&1 | Out-Null
    } catch { }
    [Console]::Error.WriteLine("  !!! env.bundle CHUA MAT KHAU/KHOA BI MAT THAT (JWT_SECRET, DB, MinIO). Bundle phai duoc coi la tai lieu mat.")

    # ---------------------------------------------------------------- 5. images
    Write-Step "[5/8] Image (MinIO/mc duoc build tu infra\minio, khong can dong goi)"
    $legacy = @()
    foreach ($l in (Get-Content -LiteralPath (Join-Path $InfraDir "compose.yaml"))) {
        if ($l -match '^\s*image:\s*(quay\.io/\S+)') { $legacy += $Matches[1] }
    }
    $legacy = @($legacy | Sort-Object -Unique)
    $imagesArch = ""
    $imgInfo = "  (khong co - moi image deu build/pull duoc tren server moi)`n"
    if ($legacy.Count -gt 0) {
        $imgInfo = ""
        $archs = @()
        foreach ($img in $legacy) {
            $a = (Get-NativeOutput "docker" @("image", "inspect", "--format", "{{.Os}}/{{.Architecture}}", $img))[0]
            $imgInfo += "  $img  $a`n"
            $archs += $a
        }
        Write-LfFile (Join-Path $B "images.list") (($legacy -join "`n") + "`n")
        Invoke-Native "docker" (@("save", "-o", (Join-Path $B "images.tar")) + $legacy)
        $imagesArch = (($archs | Sort-Object -Unique) -join " ")
        # gzip with .NET (no external gzip on Windows)
        $inS = [System.IO.File]::OpenRead((Join-Path $B "images.tar"))
        $outS = [System.IO.File]::Create((Join-Path $B "images.tar.gz"))
        try {
            $gz = New-Object System.IO.Compression.GZipStream($outS, [System.IO.Compression.CompressionMode]::Compress)
            try { $inS.CopyTo($gz) } finally { $gz.Close() }
        } finally { $inS.Close(); $outS.Close() }
        Remove-Item -LiteralPath (Join-Path $B "images.tar") -Force
        Write-Info "-> images.tar.gz, kien truc: $imagesArch"
    } else { Write-Info "compose.yaml khong ghim image quay.io: bo qua images.tar.gz." }

    # ---------------------------------------------------------------- 6. installer + docs
    Write-Step "[6/8] Installer + README"
    foreach ($f in @("install.sh", "install.ps1", "README-MIGRATION.md")) {
        $src = Join-Path $PSScriptRoot $f
        if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $B $f) }
    }
    $createdUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $dirtyText = "false"; if ($dirty) { $dirtyText = "true" }
    $confText = "BUNDLE_VERSION=1`nBUNDLE_NAME=$name`nPROJECT=$Project`nDEMO=$demoFlag`nGIT_COMMIT=$commit`nGIT_DIRTY=$dirtyText`nFLYWAY_VERSION=$flyway`nIMAGES_ARCH=$imagesArch`nCREATED_UTC=$createdUtc`n$counts"
    Write-LfFile (Join-Path $B "bundle.conf") $confText
    $sums = ""
    foreach ($f in @("source.tar.gz", "env.bundle", "images.tar.gz", "images.list", "bundle.conf")) {
        $p = Join-Path $B $f
        if (Test-Path -LiteralPath $p) { $sums += (Get-Sha256Hex $p) + "  " + $f + "`n" }
    }
    Write-LfFile (Join-Path $B "SHA256SUMS") $sums

    # ---------------------------------------------------------------- 7. manifest
    Write-Step "[7/8] MANIFEST.txt"
    $m = "Classroom migration bundle $name`n"
    $m += "Created (UTC)   : $createdUtc  on $env:COMPUTERNAME`n"
    $m += "Compose project : $Project   (demo overlay: $demoFlag)`n"
    $m += "Git commit      : $commit  (working tree dirty: $dirtyText; source: $sourceMode)`n"
    $m += "Flyway version  : V$flyway`n"
    $m += "Bundled images  :`n$imgInfo"
    $m += "Row counts (MySQL):`n" + (($counts -split "`n" | Where-Object { $_ } | ForEach-Object { "  " + $_.Substring(6) }) -join "`n") + "`n"
    $m += "Files:`n"
    foreach ($f in (Get-ChildItem -LiteralPath $B -File)) { $m += ("  {0,-22} {1,12} bytes`n" -f $f.Name, $f.Length) }
    $m += "---- data/backup.json ----`n" + ((Get-Content -LiteralPath (Join-Path $B "data\backup.json")) -join "`n") + "`n"
    Write-LfFile (Join-Path $B "MANIFEST.txt") $m

    # ---------------------------------------------------------------- 8. pack + checksum
    Write-Step "[8/8] Dong goi bundle"
    $outFile = Join-Path $OutDir ($name + ".tar.gz")
    $partial = $outFile + ".partial"
    Invoke-Native "tar.exe" @("-czf", $partial, "-C", $Staging, $name)
    if ($Encrypt) {
        $pass = $env:BUNDLE_PASSPHRASE
        if (-not $pass) {
            $p1 = Get-PlainFromSecure (Read-Host "Passphrase ma hoa bundle" -AsSecureString)
            $p2 = Get-PlainFromSecure (Read-Host "Nhap lai passphrase" -AsSecureString)
            if (-not $p1 -or $p1 -ne $p2) { throw "Passphrase trong hoac khong khop." }
            $pass = $p1
        }
        [ClsBundleCrypt]::Encrypt($partial, ($outFile + ".enc"), $pass, 200000)
        Remove-Item -LiteralPath $partial -Force
        $outFile = $outFile + ".enc"
    } else {
        Move-Item -LiteralPath $partial -Destination $outFile
    }
    $hash = Get-Sha256Hex $outFile
    $shaPath = Join-Path $OutDir ($name + ".sha256")
    Write-LfFile $shaPath ($hash + "  " + (Split-Path -Leaf $outFile) + "`n")
    foreach ($f in @("install.sh", "install.ps1", "README-MIGRATION.md")) {
        $src = Join-Path $PSScriptRoot $f
        if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $OutDir $f) -Force }
    }
    Copy-Item -LiteralPath (Join-Path $B "MANIFEST.txt") -Destination (Join-Path $OutDir ($name + ".MANIFEST.txt")) -Force

    Write-Host ""
    Write-Host "=================================================================="
    Write-Host " BUNDLE SAN SANG"
    Write-Host "   file     : $outFile"
    Write-Host ("   size     : {0} MB" -f [int]((Get-Item -LiteralPath $outFile).Length / 1MB))
    Write-Host "   sha256   : $hash"
    Write-Host "   manifest : $(Join-Path $OutDir ($name + '.MANIFEST.txt'))"
    Write-Host ""
    if ($Encrypt) { Write-Host " !!! Bundle chua mat khau that va du lieu ca nhan (da ma hoa AES-256 - nho passphrase)." }
    else { Write-Host " !!! Bundle chua mat khau that va du lieu ca nhan (CHUA ma hoa; dung -Encrypt neu di qua kenh khong tin cay)." }
    Write-Host "     Chi chuyen qua kenh tin cay (scp/SMB noi bo). Xoa sau khi chuyen xong."
    Write-Host ""
    Write-Host " Tren server MOI (chep bundle, .sha256, install.sh hoac install.ps1):"
    Write-Host "     Linux/macOS: ./install.sh $(Split-Path -Leaf $outFile)"
    Write-Host "     Windows    : powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 $(Split-Path -Leaf $outFile) -Dir C:\classroom"
    Write-Host "=================================================================="
    $exitCode = 0
} catch {
    [Console]::Error.WriteLine("Bundle creation FAILED: " + $_.Exception.Message)
    $exitCode = 1
} finally {
    Remove-DirQuietly $Staging
}
exit $exitCode
