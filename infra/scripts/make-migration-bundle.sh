#!/usr/bin/env bash
# make-migration-bundle.sh - run on the OLD server: build ONE self-contained migration bundle
# (source code + fresh data backup + env + non-pullable images + installer).
#
# Output (default ~/Classroom-migration/):
#   classroom-bundle-<yyyyMMdd-HHmmss>.tar.gz[.enc]   the bundle
#   classroom-bundle-<...>.sha256                      checksum of that file (shasum -c compatible)
#   install.sh, README-MIGRATION.md                    copies, so they can be used before the bundle is opened
#
# Usage:
#   infra/scripts/make-migration-bundle.sh [options]
#
# Options:
#   --project NAME      Compose project holding the data (default: classroom-demo)
#   --overlay FILE      compose overlay of that project (repeatable; default: infra/compose.demo.yaml when
#                       the project is classroom-demo)
#   --out-dir DIR       where to write the bundle (default: ~/Classroom-migration)
#   --encrypt           encrypt the whole tar with openssl aes-256-cbc/pbkdf2 (asks for a passphrase, or
#                       reads BUNDLE_PASSPHRASE; setting BUNDLE_PASSPHRASE alone also enables encryption)
#   --head-only         source = `git archive HEAD` only (default: HEAD + local modifications + untracked
#                       files that are not git-ignored, so scripts you have not committed yet travel too)
#   --strict            refuse to run when the git working tree is dirty
#   --no-neo4j          skip the Neo4j dump (the default includes it; neo4j is stopped for ~20 s)
#
# THE BUNDLE CONTAINS SECRETS (env.bundle) AND PERSONAL DATA. Copy it over a trusted channel (scp/rsync
# over SSH), keep it chmod 600, delete it after the migration - or use --encrypt.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
INFRA_DIR="$REPO_DIR/infra"

PROJECT="classroom-demo"
OVERLAYS_OPT=()
OUT_BASE="$HOME/Classroom-migration"
ENCRYPT=false
HEAD_ONLY=false
STRICT=false
NEO4J=true

while [ $# -gt 0 ]; do
  case "$1" in
    --project)   PROJECT="${2:?--project needs a value}"; shift 2 ;;
    --overlay)   OVERLAYS_OPT+=("${2:?--overlay needs a value}"); shift 2 ;;
    --out-dir)   OUT_BASE="${2:?--out-dir needs a value}"; shift 2 ;;
    --encrypt)   ENCRYPT=true; shift ;;
    --head-only) HEAD_ONLY=true; shift ;;
    --strict)    STRICT=true; shift ;;
    --no-neo4j)  NEO4J=false; shift ;;
    -h|--help)   sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)           die "Unknown option: $1" ;;
  esac
done
[ -z "${BUNDLE_PASSPHRASE:-}" ] || ENCRYPT=true

if [ ${#OVERLAYS_OPT[@]} -eq 0 ] && [ "$PROJECT" = "classroom-demo" ]; then
  OVERLAYS_OPT=("$INFRA_DIR/compose.demo.yaml")
fi
DEMO=0
for o in ${OVERLAYS_OPT[@]+"${OVERLAYS_OPT[@]}"}; do
  case "$o" in *compose.demo.yaml) DEMO=1 ;; esac
done

STAGING=""
FINAL_PARTIAL=""
cleanup() {
  local rc=$?
  [ -z "$STAGING" ] || rm -rf "$STAGING"
  [ -z "$FINAL_PARTIAL" ] || rm -f "$FINAL_PARTIAL"
  [ "$rc" -eq 0 ] || echo "Bundle creation FAILED (exit code $rc). Nothing usable was left behind." >&2
}
trap cleanup EXIT

# ------------------------------------------------------------------ 1. preflight
step "[1/8] Kiem tra moi truong (preflight)"
for c in docker git tar gzip openssl; do command -v "$c" >/dev/null 2>&1 || die "Thieu lenh '$c'."; done
docker info >/dev/null 2>&1 || die "Docker khong chay."
[ -f "$INFRA_DIR/.env" ] || die "Khong thay infra/.env - day la file chua bi mat can dua sang server moi."
docker compose version >/dev/null 2>&1 || die "Can docker compose v2."
ids="$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" | wc -l | tr -d ' ')"
[ "$ids" -ge 4 ] || die "Project '$PROJECT' chua chay du cac container (thay $ids). Khoi dong stack truoc khi tao bundle (--project NAME neu ten khac)."

mkdir -p "$OUT_BASE"
chmod 700 "$OUT_BASE"
OUT_BASE="$(cd "$OUT_BASE" && pwd)"
free_kb="$(df -Pk "$OUT_BASE" | awk 'NR==2 {print $4}')"
[ "$free_kb" -ge $((12 * 1024 * 1024)) ] || die "Can it nhat 12 GB trong $OUT_BASE (con $((free_kb / 1024 / 1024)) GB)."
info "docker OK, project $PROJECT dang chay ($ids container), con $((free_kb / 1024 / 1024)) GB trong $OUT_BASE"

DIRTY=false
if [ -n "$(git -C "$REPO_DIR" status --porcelain 2>/dev/null)" ]; then
  DIRTY=true
  if [ "$STRICT" = true ]; then die "Working tree chua commit (--strict). Commit truoc hoac bo --strict."; fi
  warn "Working tree chua sach (co thay doi chua commit). Se dong goi: HEAD + thay doi cuc bo + file moi chua ignore (ghi vao MANIFEST)."
  [ "$HEAD_ONLY" = false ] || warn "--head-only: chi dong goi commit HEAD, bo qua thay doi cuc bo."
fi
COMMIT="$(git -C "$REPO_DIR" rev-parse HEAD)"
STAMP="$(date +%Y%m%d-%H%M%S)"
NAME="classroom-bundle-$STAMP"
STAGING="$(mktemp -d "$OUT_BASE/.staging-$STAMP.XXXXXX")"
chmod 700 "$STAGING"
B="$STAGING/$NAME"
mkdir -p "$B"

# ------------------------------------------------------------------ 2. source
step "[2/8] Dong goi ma nguon ($COMMIT)"
export COPYFILE_DISABLE=1   # macOS tar: no ._ AppleDouble files
if [ "$HEAD_ONLY" = true ]; then
  git -C "$REPO_DIR" archive --format=tar HEAD | gzip -6 > "$B/source.tar.gz"
  SOURCE_MODE="git archive HEAD"
else
  LIST="$STAGING/files.list"
  : > "$LIST"
  while IFS= read -r -d '' f; do
    [ -e "$REPO_DIR/$f" ] || [ -L "$REPO_DIR/$f" ] || continue   # deleted in the working tree
    printf '%s\n' "$f" >> "$LIST"
  done < <(git -C "$REPO_DIR" ls-files -z --cached --others --exclude-standard)
  # shellcheck disable=SC2094
  tar -C "$REPO_DIR" -czf "$B/source.tar.gz" -T "$LIST"
  SOURCE_MODE="working tree (tracked + untracked, minus .gitignore)"
fi
tar -tzf "$B/source.tar.gz" | grep -qx 'infra/compose.yaml' || die "source.tar.gz does not contain infra/compose.yaml"
if tar -tzf "$B/source.tar.gz" | grep -E '(^|/)\.env$' >/dev/null; then die "source.tar.gz contains a .env file - refusing."; fi
info "-> source.tar.gz ($(file_size "$B/source.tar.gz") bytes, $SOURCE_MODE)"

# ------------------------------------------------------------------ 3. data backup
step "[3/8] Sao luu du lieu moi (backup.sh: MySQL + MongoDB + Neo4j + MinIO)"
BACKUP_ARGS=(--out-dir "$STAGING/backup" --project-name "$PROJECT")
for o in ${OVERLAYS_OPT[@]+"${OVERLAYS_OPT[@]}"}; do BACKUP_ARGS+=(--overlay "$o"); done
[ "$NEO4J" = false ] || BACKUP_ARGS+=(--include-neo4j-dump)
bash "$SCRIPT_DIR/backup.sh" "${BACKUP_ARGS[@]}"
BK="$(find "$STAGING/backup" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
[ -n "$BK" ] && [ -f "$BK/backup.json" ] || die "backup.sh khong tao backup.json"
mv "$BK" "$B/data"
rmdir "$STAGING/backup"
bash "$SCRIPT_DIR/restore.sh" "$B/data" --verify-only >/dev/null || die "Verify backup that bai."
info "backup.json da duoc kiem tra lai (sha256 tung thanh phan)."

# row counts + Flyway for MANIFEST / post-install verification
MYSQL_CID="$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=mysql" | head -n 1)"
mysql_q() { docker exec "$MYSQL_CID" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B "$MYSQL_DATABASE" -e "$0"' "$1" 2>/dev/null | tr -d '\r'; }
FLYWAY="$(mysql_q 'select max(cast(version as unsigned)) from flyway_schema_history where success=1')"
COUNTS=""
for t in users classrooms class_members courses lessons posts blog_posts media_assets exams orders; do
  n="$(mysql_q "select count(*) from $t")" || n=""
  COUNTS+="COUNT_$t=${n:-?}"$'\n'
done

# ------------------------------------------------------------------ 4. env
step "[4/8] Env file"
cp "$INFRA_DIR/.env" "$B/env.bundle"
chmod 600 "$B/env.bundle"
echo "  !!! env.bundle CHUA MAT KHAU/KHOA BI MAT THAT (JWT_SECRET, DB, MinIO). Bundle phai duoc coi la tai lieu mat." >&2

# ------------------------------------------------------------------ 5. images
step "[5/8] Image (MinIO/mc duoc build tu infra/minio, khong can dong goi)"
IMAGES=()
while IFS= read -r img; do IMAGES+=("$img"); done < <(sed -n 's/^[[:space:]]*image:[[:space:]]*\(quay\.io\/[^[:space:]]*\).*$/\1/p' "$INFRA_DIR/compose.yaml" | sort -u)
IMG_INFO="  (khong co - moi image deu build/pull duoc tren server moi)"$'\n'
IMAGES_ARCH=""
if [ ${#IMAGES[@]} -gt 0 ]; then
  # legacy compose files that still pin quay.io images: ship them (docker save) as before
  IMG_INFO=""
  ARCHS=""
  for img in "${IMAGES[@]}"; do
    docker image inspect "$img" >/dev/null 2>&1 || die "Image $img khong co tren may nay (docker pull/build truoc)."
    arch="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$img")"
    IMG_INFO+="  $img  $(docker image inspect --format '{{.Id}}' "$img")  $arch"$'\n'
    ARCHS+="$arch "
  done
  printf '%s\n' "${IMAGES[@]}" > "$B/images.list"
  docker save "${IMAGES[@]}" | gzip -6 > "$B/images.tar.gz"
  IMAGES_ARCH="$(printf '%s\n' $ARCHS | sort -u | tr '\n' ' ' | sed 's/ $//')"
  info "-> images.tar.gz ($(file_size "$B/images.tar.gz") bytes), kien truc: $IMAGES_ARCH"
else
  info "compose.yaml khong ghim image quay.io: bo qua images.tar.gz."
fi

# ------------------------------------------------------------------ 6. installer + docs
step "[6/8] Installer + README"
cp "$SCRIPT_DIR/install.sh" "$B/install.sh"; chmod 755 "$B/install.sh"
[ ! -f "$SCRIPT_DIR/install.ps1" ] || cp "$SCRIPT_DIR/install.ps1" "$B/install.ps1"
[ ! -f "$SCRIPT_DIR/README-MIGRATION.md" ] || cp "$SCRIPT_DIR/README-MIGRATION.md" "$B/README-MIGRATION.md"
cat > "$B/bundle.conf" <<EOF
BUNDLE_VERSION=1
BUNDLE_NAME=$NAME
PROJECT=$PROJECT
DEMO=$DEMO
GIT_COMMIT=$COMMIT
GIT_DIRTY=$DIRTY
FLYWAY_VERSION=$FLYWAY
IMAGES_ARCH=$IMAGES_ARCH
CREATED_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
$COUNTS
EOF
( cd "$B" && for f in source.tar.gz env.bundle images.tar.gz images.list bundle.conf; do [ -f "$f" ] || continue; printf '%s  %s\n' "$(sha256_file "$f")" "$f"; done > SHA256SUMS )

# ------------------------------------------------------------------ 7. manifest
step "[7/8] MANIFEST.txt"
{
  echo "Classroom migration bundle $NAME"
  echo "Created (UTC)   : $(date -u +%Y-%m-%dT%H:%M:%SZ)  on $(hostname)"
  echo "Compose project : $PROJECT   (demo overlay: $([ "$DEMO" = 1 ] && echo yes || echo no))"
  echo "Git commit      : $COMMIT  (working tree dirty: $DIRTY; source: $SOURCE_MODE)"
  echo "Flyway version  : V$FLYWAY"
  echo "Image arch      : $IMAGES_ARCH   (docker host: $(docker info --format '{{.OSType}}/{{.Architecture}}' | sed 's|/aarch64|/arm64|;s|/x86_64|/amd64|'))"
  echo "Bundled images  :"
  printf '%s' "$IMG_INFO"
  echo "Row counts (MySQL):"
  sed 's/^COUNT_/  /' <<< "$COUNTS" | sed '/^$/d'
  echo "Files:"
  ( cd "$B" && for f in *; do [ -f "$f" ] && printf '  %-22s %12s bytes\n' "$f" "$(file_size "$f")"; done )
  echo "  data/                  $(du -sk "$B/data" | cut -f1) KiB (backup.json below)"
  echo "---- data/backup.json ----"
  cat "$B/data/backup.json"
} > "$B/MANIFEST.txt"

# ------------------------------------------------------------------ 8. pack (+ encrypt) + checksum
step "[8/8] Dong goi bundle"
OUT_FILE="$OUT_BASE/$NAME.tar.gz"
FINAL_PARTIAL="$OUT_FILE.partial"
tar -C "$STAGING" -czf "$FINAL_PARTIAL" "$NAME"
if [ "$ENCRYPT" = true ]; then
  if [ -z "${BUNDLE_PASSPHRASE:-}" ]; then
    [ -t 0 ] || die "--encrypt can passphrase: dat BUNDLE_PASSPHRASE hoac chay trong terminal."
    read -r -s -p "Passphrase ma hoa bundle: " p1; echo
    read -r -s -p "Nhap lai passphrase: " p2; echo
    [ "$p1" = "$p2" ] && [ -n "$p1" ] || die "Passphrase trong hoac khong khop."
    export BUNDLE_PASSPHRASE="$p1"
  fi
  openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -in "$FINAL_PARTIAL" -out "$OUT_FILE.enc" -pass env:BUNDLE_PASSPHRASE
  rm -f "$FINAL_PARTIAL"
  FINAL_PARTIAL=""
  OUT_FILE="$OUT_FILE.enc"
else
  mv "$FINAL_PARTIAL" "$OUT_FILE"
  FINAL_PARTIAL=""
fi
chmod 600 "$OUT_FILE"
printf '%s  %s\n' "$(sha256_file "$OUT_FILE")" "$(basename "$OUT_FILE")" > "${OUT_FILE%.tar.gz*}.sha256"
cp "$SCRIPT_DIR/install.sh" "$OUT_BASE/install.sh"; chmod 755 "$OUT_BASE/install.sh"
[ ! -f "$SCRIPT_DIR/install.ps1" ] || cp "$SCRIPT_DIR/install.ps1" "$OUT_BASE/install.ps1"
[ ! -f "$SCRIPT_DIR/README-MIGRATION.md" ] || cp "$SCRIPT_DIR/README-MIGRATION.md" "$OUT_BASE/README-MIGRATION.md"
cp "$B/MANIFEST.txt" "$OUT_BASE/$NAME.MANIFEST.txt"

echo ""
echo "=================================================================="
echo " BUNDLE SAN SANG"
echo "   file     : $OUT_FILE"
echo "   size     : $(du -h "$OUT_FILE" | cut -f1)"
echo "   sha256   : $(cut -d' ' -f1 "${OUT_FILE%.tar.gz*}.sha256")"
echo "   manifest : $OUT_BASE/$NAME.MANIFEST.txt"
echo ""
echo " !!! Bundle chua mat khau that va du lieu ca nhan$([ "$ENCRYPT" = true ] && echo " (da ma hoa AES-256 - nho passphrase)" || echo " (CHUA ma hoa; dung --encrypt neu di qua kenh khong tin cay)")."
echo "     Chi chuyen qua scp/rsync (SSH). Xoa sau khi chuyen xong."
[ "$IMAGES_ARCH" = "linux/arm64" ] && echo " !!! Image MinIO/mc trong bundle la linux/arm64: server x86_64 se KHONG chay duoc -> xem README-MIGRATION.md (--minio-image/--mc-image)."
echo ""
echo " Tren server MOI (chep 3 file: bundle, .sha256, install.sh):"
echo "     ./install.sh $(basename "$OUT_FILE")"
echo "     (Windows) powershell -NoProfile -ExecutionPolicy Bypass -File .\\install.ps1 $(basename "$OUT_FILE") -Dir C:\\classroom"
echo "=================================================================="
