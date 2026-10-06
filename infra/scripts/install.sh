#!/usr/bin/env bash
# install.sh - ONE command on the NEW server: unpack a migration bundle, build and start the whole
# Classroom stack and restore the data (MySQL, MongoDB, Neo4j, MinIO) into it.
#
#   ./install.sh [BUNDLE.tar.gz[.enc]] [options]
#
# BUNDLE defaults to the newest classroom-bundle-*.tar.gz* next to this script (or, when this script
# sits inside an already extracted bundle, that folder).
#
# Options:
#   --dir DIR            install location (default ./classroom)
#   --project NAME       Compose project name (default: the one in the bundle, normally classroom-demo)
#   --port N             public frontend port (default: FRONTEND_PORT of the bundle env)
#   --origin URL         public origin, e.g. https://lop.example.com  (sets APP_CORS_ALLOWED_ORIGINS;
#                        APP_COOKIE_SECURE=true when https)
#   --bind ADDR          HOST_BIND_ADDRESS: 127.0.0.1 (default) or 0.0.0.0 to expose frontend+backend
#   --minio-endpoint URL browser-facing MinIO URL used in presigned links (MINIO_EXTERNAL_ENDPOINT)
#   --no-demo            do NOT apply infra/compose.demo.yaml (see the warning printed at the end)
#   --no-restore         start a fresh EMPTY stack, restore nothing
#   --overlay FILE       extra compose file (repeatable; relative to the install dir or absolute)
#   --set KEY=VALUE      extra override in infra/.env (repeatable; e.g. --set MYSQL_PORT=3317)
#   --minio-image IMG    pullable image to use (retagged) when the bundled MinIO image does not fit this CPU
#   --mc-image IMG       same for the mc image
#   --yes                non-interactive: accept overwriting an existing installation's data
#   --passphrase         ask for the bundle passphrase (or set BUNDLE_PASSPHRASE / --passphrase-file FILE)
#   --passphrase-file F  read the passphrase from a file
#   --keep-extracted     keep the unpacked bundle (contains data + secrets) in <dir>/.bundle after install
#
# Safe to re-run: existing volumes are detected; nothing is overwritten without --yes (or typing the
# project name), a safety backup is taken first and volumes are never deleted.
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# ---------------------------------------------------------------- output helpers
STEP_NO=0; STEP_TOTAL=11
step() { STEP_NO=$((STEP_NO + 1)); printf '\n==> [%s/%s] %s\n' "$STEP_NO" "$STEP_TOTAL" "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf 'CANH BAO: %s\n' "$*" >&2; }
die()  { printf 'LOI: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- arguments
BUNDLE=""; DIR="./classroom"; PROJECT=""; PORT=""; ORIGIN=""; BIND=""; MINIO_ENDPOINT=""
NO_DEMO=false; NO_RESTORE=false; YES=false; ASK_PASS=false; PASS_FILE=""; KEEP_EXTRACTED=false
MINIO_IMAGE_OVERRIDE=""; MC_IMAGE_OVERRIDE=""
EXTRA_OVERLAYS=(); SETS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)             DIR="${2:?--dir needs a value}"; shift 2 ;;
    --project)         PROJECT="${2:?--project needs a value}"; shift 2 ;;
    --port)            PORT="${2:?--port needs a value}"; shift 2 ;;
    --origin)          ORIGIN="${2:?--origin needs a value}"; shift 2 ;;
    --bind)            BIND="${2:?--bind needs a value}"; shift 2 ;;
    --minio-endpoint)  MINIO_ENDPOINT="${2:?--minio-endpoint needs a value}"; shift 2 ;;
    --no-demo)         NO_DEMO=true; shift ;;
    --no-restore)      NO_RESTORE=true; shift ;;
    --overlay)         EXTRA_OVERLAYS+=("${2:?--overlay needs a value}"); shift 2 ;;
    --set)             SETS+=("${2:?--set needs KEY=VALUE}"); shift 2 ;;
    --minio-image)     MINIO_IMAGE_OVERRIDE="${2:?}"; shift 2 ;;
    --mc-image)        MC_IMAGE_OVERRIDE="${2:?}"; shift 2 ;;
    --yes|-y)          YES=true; shift ;;
    --passphrase)      ASK_PASS=true; shift ;;
    --passphrase-file) PASS_FILE="${2:?--passphrase-file needs a value}"; shift 2 ;;
    --keep-extracted)  KEEP_EXTRACTED=true; shift ;;
    -h|--help)         sed -n '2,31p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)                die "Tuy chon khong hop le: $1 (xem --help)" ;;
    *)                 if [ -z "$BUNDLE" ]; then BUNDLE="$1"; shift; else die "Tham so thua: $1"; fi ;;
  esac
done

# ---------------------------------------------------------------- generic helpers
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
conf_value() { # file key
  [ -f "$1" ] || return 0
  sed -n "s/^$2=//p" "$1" | tail -n 1 | tr -d '\r'
}
env_value() { conf_value "$ENV_FILE" "$1"; }
# Replace/append KEY=VALUE in the env file without passing the value through sed/awk (values may hold any char).
set_env() { # KEY VALUE
  local key="$1" val="$2" tmp line found=false
  tmp="$(umask 077; mktemp "$ENV_FILE.XXXXXX")"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      "$key="*) if [ "$found" = false ]; then printf '%s=%s\n' "$key" "$val"; found=true; fi ;;
      *) printf '%s\n' "$line" ;;
    esac
  done < "$ENV_FILE" > "$tmp"
  if [ "$found" = false ]; then printf '%s=%s\n' "$key" "$val" >> "$tmp"; fi
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
}
ask_yes() { # prompt ; returns 0 when confirmed
  [ "$YES" = true ] && return 0
  [ -t 0 ] || die "Can xac nhan nhung khong co terminal: chay lai voi --yes."
  local a; read -r -p "$1 [y/N] " a
  case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}
host_arch() {
  local os arch
  os="$(docker info --format '{{.OSType}}' 2>/dev/null)"; arch="$(docker info --format '{{.Architecture}}' 2>/dev/null)"
  case "$arch" in aarch64) arch=arm64 ;; x86_64) arch=amd64 ;; esac
  printf '%s/%s' "$os" "$arch"
}
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }

WORK=""
DIR_ABS=""
cleanup() {
  local rc=$?
  if [ -n "$WORK" ] && [ -d "$WORK" ] && [ "$KEEP_EXTRACTED" != true ]; then rm -rf "$WORK"; fi
  if [ "$rc" -ne 0 ]; then
    printf '\nCAI DAT THAT BAI (ma loi %s). Du lieu cu (neu co) khong bi xoa; volume Docker khong bi xoa.\n' "$rc" >&2
    [ -z "$DIR_ABS" ] || [ -z "${PROJECT:-}" ] || printf 'Xem log: docker compose -p %s logs --tail 100   |   chay lai ./install.sh sau khi sua loi (an toan).\n' "${PROJECT:-?}" >&2
  fi
}
trap cleanup EXIT

# ================================================================= 1. preflight
step "Kiem tra moi truong (preflight)"
for c in docker tar gzip openssl curl; do command -v "$c" >/dev/null 2>&1 || die "Thieu lenh '$c'. Hay cai dat truoc."; done
docker info >/dev/null 2>&1 || die "Docker chua chay (hoac user nay khong co quyen). Khoi dong Docker roi chay lai."
docker compose version >/dev/null 2>&1 || die "Can Docker Compose v2 ('docker compose')."
mem="$(docker info --format '{{.MemTotal}}')"
[ "${mem:-0}" -ge 5700000000 ] || die "Docker chi co $((mem / 1024 / 1024)) MB RAM; can >= 6 GB (Docker Desktop: Settings > Resources)."
info "Docker $(docker version --format '{{.Server.Version}}'), Compose $(docker compose version --short), RAM $((mem / 1024 / 1024 / 1024)) GB, $(host_arch)"
mkdir -p "$DIR"
DIR_ABS="$(cd "$DIR" && pwd)"
free_kb="$(df -Pk "$DIR_ABS" | awk 'NR==2 {print $4}')"
[ "$free_kb" -ge $((10 * 1024 * 1024)) ] || die "Can >= 10 GB trong $DIR_ABS (con $((free_kb / 1024 / 1024)) GB)."
info "Dia trong: $((free_kb / 1024 / 1024)) GB tai $DIR_ABS"

# ================================================================= 2. locate + verify bundle
step "Tim va kiem tra bundle"
WORK="$DIR_ABS/.bundle"
if [ -z "$BUNDLE" ] && [ -f "$SELF_DIR/source.tar.gz" ] && [ -d "$SELF_DIR/data" ]; then
  BUNDLE_DIR="$SELF_DIR"
  info "Dung bundle da giai nen: $BUNDLE_DIR"
else
  if [ -z "$BUNDLE" ]; then
    BUNDLE="$(ls -1t "$SELF_DIR"/classroom-bundle-*.tar.gz* 2>/dev/null | grep -v '\.sha256$' | head -n 1 || true)"
    [ -n "$BUNDLE" ] || die "Khong thay classroom-bundle-*.tar.gz[.enc] canh install.sh. Truyen duong dan: ./install.sh <bundle>"
  fi
  [ -f "$BUNDLE" ] || die "Khong thay file bundle: $BUNDLE"
  BUNDLE="$(cd "$(dirname "$BUNDLE")" && pwd)/$(basename "$BUNDLE")"
  info "Bundle: $BUNDLE ($(du -h "$BUNDLE" | cut -f1))"
  SHA_FILE="${BUNDLE%.tar.gz*}.sha256"
  if [ -f "$SHA_FILE" ]; then
    want="$(cut -d' ' -f1 "$SHA_FILE")"
    got="$(sha256_file "$BUNDLE")"
    [ "$want" = "$got" ] || die "SHA-256 cua bundle KHONG khop (file hong hoac bi sua khi chuyen). Chep lai file."
    info "SHA-256 bundle khop: ${got:0:16}..."
  else
    warn "Khong co file .sha256 canh bundle - bo qua kiem tra checksum ngoai."
  fi
  rm -rf "$WORK"; mkdir -p "$WORK"; chmod 700 "$WORK"
  if [ "$(head -c 8 "$BUNDLE")" = "Salted__" ]; then
    if [ -n "$PASS_FILE" ]; then BUNDLE_PASSPHRASE="$(head -n 1 "$PASS_FILE")"; export BUNDLE_PASSPHRASE; fi
    if [ -z "${BUNDLE_PASSPHRASE:-}" ]; then
      [ -t 0 ] || die "Bundle da ma hoa: dat BUNDLE_PASSPHRASE, hoac --passphrase-file, hoac chay trong terminal."
      read -r -s -p "Passphrase cua bundle: " BUNDLE_PASSPHRASE; echo; export BUNDLE_PASSPHRASE
    fi
    info "Giai ma + giai nen bundle (AES-256)..."
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in "$BUNDLE" -pass env:BUNDLE_PASSPHRASE 2>/dev/null | tar -xz -C "$WORK" \
      || die "Giai ma that bai: sai passphrase hoac file hong."
  else
    [ "$ASK_PASS" = false ] || warn "--passphrase duoc dua nhung bundle khong ma hoa."
    info "Giai nen bundle..."
    tar -xzf "$BUNDLE" -C "$WORK"
  fi
  BUNDLE_DIR="$(find "$WORK" -mindepth 1 -maxdepth 1 -type d -name 'classroom-bundle-*' | head -n 1)"
  [ -n "$BUNDLE_DIR" ] || die "Bundle khong dung dinh dang (khong co thu muc classroom-bundle-*)."
fi
[ -f "$BUNDLE_DIR/bundle.conf" ] && [ -f "$BUNDLE_DIR/source.tar.gz" ] && [ -f "$BUNDLE_DIR/env.bundle" ] || die "Bundle thieu file (bundle.conf/source.tar.gz/env.bundle)."
( cd "$BUNDLE_DIR" && while read -r h f; do
    [ -f "$f" ] || { echo "thieu $f" >&2; exit 1; }
    [ "$(sha256_file "$f")" = "$h" ] || { echo "sai checksum $f" >&2; exit 1; }
  done < SHA256SUMS ) || die "Noi dung bundle khong khop SHA256SUMS."
info "Cac file ben trong bundle khop SHA256SUMS."
CONF="$BUNDLE_DIR/bundle.conf"
BUNDLE_PROJECT="$(conf_value "$CONF" PROJECT)"
BUNDLE_DEMO="$(conf_value "$CONF" DEMO)"
PROJECT="${PROJECT:-${BUNDLE_PROJECT:-classroom-demo}}"
printf '%s' "$PROJECT" | grep -Eq '^[a-z0-9][a-z0-9_-]*$' || die "Ten project khong hop le: $PROJECT"
info "Commit $(conf_value "$CONF" GIT_COMMIT | cut -c1-10), Flyway V$(conf_value "$CONF" FLYWAY_VERSION), project '$PROJECT'"
USE_DEMO=true
{ [ "$NO_DEMO" = true ] || [ "$BUNDLE_DEMO" != "1" ]; } && USE_DEMO=false

# ================================================================= 3. existing install?
step "Phat hien cai dat hien co"
EXISTING_VOLUMES="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" | wc -l | tr -d ' ')"
RUNNING_OWN="$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" | wc -l | tr -d ' ')"
if [ "$EXISTING_VOLUMES" -gt 0 ]; then
  warn "Project '$PROJECT' da co $EXISTING_VOLUMES volume du lieu tren may nay."
  if [ "$NO_RESTORE" = true ]; then
    info "--no-restore: se chi build/khoi dong lai, KHONG dung vao du lieu."
  else
    echo "    Khoi phuc se GHI DE du lieu hien co (MySQL/MongoDB/Neo4j/MinIO) bang du lieu trong bundle."
    if [ "$YES" != true ]; then
      [ -t 0 ] || die "Da co du lieu: chay lai voi --yes de ghi de, hoac --no-restore."
      read -r -p "    Go lai ten project '$PROJECT' de xac nhan ghi de: " typed
      [ "$typed" = "$PROJECT" ] || die "Khong khop - khong thay doi gi."
    fi
    info "Se tao ban sao luu an toan truoc khi ghi de."
  fi
else
  info "Chua co du lieu cho project '$PROJECT' - cai dat moi."
fi

# ================================================================= 4. source + env
step "Giai nen ma nguon vao $DIR_ABS"
tar -xzf "$BUNDLE_DIR/source.tar.gz" -C "$DIR_ABS"
INFRA="$DIR_ABS/infra"
[ -f "$INFRA/compose.yaml" ] || die "Ma nguon khong co infra/compose.yaml"
chmod +x "$INFRA"/scripts/*.sh 2>/dev/null || true
ENV_FILE="$INFRA/.env"
if [ -f "$ENV_FILE" ]; then
  info "Giu nguyen $ENV_FILE co san (khong ghi de bi mat). Bundle env luu o $INFRA/.env.from-bundle (chmod 600)."
  ( umask 077; cp "$BUNDLE_DIR/env.bundle" "$INFRA/.env.from-bundle" )
else
  ( umask 077; cp "$BUNDLE_DIR/env.bundle" "$ENV_FILE" )
  info "Da dat $ENV_FILE tu env.bundle (chmod 600; chua bi mat that)."
fi
chmod 600 "$ENV_FILE"

[ -z "$PORT" ] || set_env FRONTEND_PORT "$PORT"
FRONT_PORT="$(env_value FRONTEND_PORT)"; FRONT_PORT="${FRONT_PORT:-3000}"
[ -z "$BIND" ] || set_env HOST_BIND_ADDRESS "$BIND"
CORS=""
if [ -n "$ORIGIN" ]; then
  ORIGIN="${ORIGIN%/}"
  case "$ORIGIN" in http://*|https://*) ;; *) die "--origin phai bat dau bang http:// hoac https://" ;; esac
  CORS="$ORIGIN,http://localhost:$FRONT_PORT,http://127.0.0.1:$FRONT_PORT"
  case "$ORIGIN" in https://*) set_env APP_COOKIE_SECURE true ;; esac
elif [ -n "$PORT" ]; then
  CORS="http://localhost:$FRONT_PORT,http://127.0.0.1:$FRONT_PORT"
fi
[ -z "$CORS" ] || set_env APP_CORS_ALLOWED_ORIGINS "$CORS"
[ -z "$MINIO_ENDPOINT" ] || set_env MINIO_EXTERNAL_ENDPOINT "${MINIO_ENDPOINT%/}"
for kv in ${SETS[@]+"${SETS[@]}"}; do
  case "$kv" in [A-Za-z_][A-Za-z0-9_]*=*) set_env "${kv%%=*}" "${kv#*=}" ;; *) die "--set can KEY=VALUE, nhan: $kv" ;; esac
done
# Compose command line (no secrets on it; the env file is read by compose itself)
COMPOSE_FILES=(-f compose.yaml)
[ "$USE_DEMO" = false ] || COMPOSE_FILES+=(-f compose.demo.yaml)
OVERLAY_ABS=()
for o in ${EXTRA_OVERLAYS[@]+"${EXTRA_OVERLAYS[@]}"}; do
  case "$o" in /*) f="$o" ;; *) f="$(cd "$(dirname "$o")" && pwd)/$(basename "$o")" ;; esac
  [ -f "$f" ] || die "Overlay khong ton tai: $o"
  COMPOSE_FILES+=(-f "$f"); OVERLAY_ABS+=("$f")
done
dc() { ( cd "$INFRA" && docker compose -p "$PROJECT" "${COMPOSE_FILES[@]}" --env-file .env "$@" ); }
{
  echo "# written by install.sh - read by infra/scripts/server-up.sh (no secrets here)"
  echo "PROJECT=$PROJECT"
  echo "DEMO=$([ "$USE_DEMO" = true ] && echo 1 || echo 0)"
  echo "OVERLAYS=${OVERLAY_ABS[*]-}"
} > "$INFRA/.server.conf"
dc config -q || die "Cau hinh compose khong hop le (thieu bien trong .env?)."
# container_name is fixed in compose.yaml: containers of ANOTHER project with the same name would collide
# (stopped ones too). Names are read from the resolved config, so an overlay that renames them is honoured.
while IFS= read -r n; do
  [ -n "$n" ] || continue
  owner="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$n" 2>/dev/null || true)"
  if [ -n "$owner" ] && [ "$owner" != "$PROJECT" ]; then
    die "Container '$n' (con ton tai, co the dang dung) thuoc project '$owner'; ten container co dinh trong compose.yaml nen se trung. Dung --project $owner de nang cap tai cho, hoac xoa/doi ten stack do (khong dung --yes de ghi de len no)."
  fi
done < <(dc config | sed -n 's/^[[:space:]]*container_name:[[:space:]]*//p')
BACKEND_PORT="$(env_value BACKEND_PORT)"; BACKEND_PORT="${BACKEND_PORT:-8080}"
BIND_ADDR="$(env_value HOST_BIND_ADDRESS)"; BIND_ADDR="${BIND_ADDR:-127.0.0.1}"
info "project=$PROJECT  frontend=:$FRONT_PORT  backend=:$BACKEND_PORT  bind=$BIND_ADDR  demo=$USE_DEMO"

# ports
if [ "$RUNNING_OWN" -eq 0 ]; then
  for v in FRONTEND_PORT BACKEND_PORT MYSQL_PORT MONGO_PORT NEO4J_HTTP_PORT NEO4J_BOLT_PORT MINIO_PORT MINIO_CONSOLE_PORT; do
    case "$v" in
      FRONTEND_PORT) d=3000 ;; BACKEND_PORT) d=8080 ;; MYSQL_PORT) d=3307 ;; MONGO_PORT) d=27017 ;;
      NEO4J_HTTP_PORT) d=7474 ;; NEO4J_BOLT_PORT) d=7687 ;; MINIO_PORT) d=9000 ;; MINIO_CONSOLE_PORT) d=9001 ;;
    esac
    p="$(env_value "$v")"; p="${p:-$d}"
    if port_busy "$p"; then die "Cong $p ($v) dang bi chuong trinh khac dung. Doi bang --set $v=<cong khac> (hoac --port cho frontend)."; fi
  done
  info "Cac cong can dung deu dang trong."
fi

# ================================================================= 5. verify backup manifest
step "Kiem tra du lieu sao luu (backup.json: size + sha256 tung thanh phan)"
RESTORE_DATA=true
[ "$NO_RESTORE" = false ] || RESTORE_DATA=false
if [ "$RESTORE_DATA" = true ]; then
  bash "$INFRA/scripts/restore.sh" "$BUNDLE_DIR/data" --verify-only | sed 's/^/    /'
else
  info "--no-restore: bo qua."
fi

# ================================================================= 6. images
step "Nap image khong pull duoc (MinIO/mc)"
HOSTARCH="$(host_arch)"
LOADED=false
image_arch_ok() { [ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$1" 2>/dev/null || true)" = "$HOSTARCH" ]; }
ensure_image() {
  local img="$1" ov="" id
  case "$img" in */minio:*) ov="$MINIO_IMAGE_OVERRIDE" ;; */mc:*) ov="$MC_IMAGE_OVERRIDE" ;; esac
  if image_arch_ok "$img"; then info "da co: $img"; return 0; fi
  if [ -n "$ov" ]; then
    docker pull "$ov" >/dev/null || die "Khong pull duoc $ov"
    docker tag "$ov" "$img"; info "dung $ov lam $img"; return 0
  fi
  if [ "$LOADED" = false ] && [ -f "$BUNDLE_DIR/images.tar.gz" ]; then
    info "docker load images.tar.gz ..."
    gzip -dc "$BUNDLE_DIR/images.tar.gz" | docker load | sed 's/^/    /'
    LOADED=true
  fi
  if image_arch_ok "$img"; then info "da nap: $img"; return 0; fi
  info "Image trong bundle khong hop kien truc $HOSTARCH; thu pull $img ..."
  if docker pull "$img" >/dev/null 2>&1 && image_arch_ok "$img"; then info "da pull: $img"; return 0; fi
  die "Khong co image $img cho $HOSTARCH (bundle: $(conf_value "$CONF" IMAGES_ARCH); registry quay.io tu choi). Chay lai voi --minio-image <image> va --mc-image <image> (vi du bds-minio/minio, minio/mc ban pull duoc), xem README-MIGRATION.md."
}
if [ -f "$BUNDLE_DIR/images.list" ]; then
  while IFS= read -r img; do [ -z "$img" ] || ensure_image "$img"; done < "$BUNDLE_DIR/images.list"
else
  info "Bundle khong co images.list - bo qua."
fi

# ================================================================= 7. data services
step "Khoi dong dich vu du lieu (mysql, mongodb, neo4j, minio, minio-init)"
dc up -d mysql mongodb neo4j minio minio-init
wait_service() { # service seconds
  local cid waited=0 health state
  while [ "$waited" -lt "$2" ]; do
    cid="$(dc ps -a -q "$1" | head -n 1)"
    if [ -n "$cid" ]; then
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid")"
      state="$(docker inspect --format '{{.State.Status}}' "$cid")"
      [ "$health" != "healthy" ] || return 0
      [ "$health" != "none" ] || [ "$state" != "running" ] || return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  return 1
}
for s in mysql mongodb neo4j minio; do
  printf '    cho %s healthy ... ' "$s"
  wait_service "$s" 240 || { echo; die "$s khong healthy sau 240 s: docker compose -p $PROJECT logs $s"; }
  echo "OK"
done
cid="$(dc ps -a -q minio-init | head -n 1)"
for _ in $(seq 1 30); do
  [ "$(docker inspect --format '{{.State.Status}}' "$cid")" = "exited" ] && break
  sleep 2
done
[ "$(docker inspect --format '{{.State.ExitCode}}' "$cid")" = "0" ] || die "minio-init loi (tao bucket): docker logs classroom-minio-init"
info "minio-init xong (bucket san sang)."

# ================================================================= 8. safety backup + restore
step "Khoi phuc du lieu (truoc khi chay backend)"
if [ "$RESTORE_DATA" = true ]; then
  if [ "$EXISTING_VOLUMES" -gt 0 ] && [ -n "$(dc ps -q mysql)" ]; then
    SAFETY="$DIR_ABS/backups/pre-install"
    info "Ban sao luu an toan du lieu hien co -> $SAFETY"
    SAFE_ARGS=(--out-dir "$SAFETY" --project-name "$PROJECT" --infra-dir "$INFRA")
    [ "$USE_DEMO" = false ] || SAFE_ARGS+=(--overlay "$INFRA/compose.demo.yaml")
    bash "$INFRA/scripts/backup.sh" "${SAFE_ARGS[@]}" >/dev/null 2>&1 \
      && info "da luu (MySQL/MongoDB/MinIO)." || warn "Khong tao duoc ban sao luu an toan (co the DB hien co rong)."
  fi
  R_ARGS=(--project-name "$PROJECT" --yes --confirm-project "$PROJECT" --infra-dir "$INFRA" --mirror-remove)
  [ "$USE_DEMO" = false ] || R_ARGS+=(--overlay "$INFRA/compose.demo.yaml")
  for o in ${OVERLAY_ABS[@]+"${OVERLAY_ABS[@]}"}; do R_ARGS+=(--overlay "$o"); done
  [ ! -f "$BUNDLE_DIR/data/neo4j.dump" ] || R_ARGS+=(--include-neo4j)
  bash "$INFRA/scripts/restore.sh" "$BUNDLE_DIR/data" "${R_ARGS[@]}" | sed 's/^/    /'
  echo "    Khoi phuc xong."
else
  info "--no-restore: stack trong, khong khoi phuc."
fi

# ================================================================= 9. backend + frontend
step "Build + khoi dong backend, frontend (build Maven/npm lan dau mat vai phut)"
dc up -d --build backend frontend
printf '    cho backend /api/v1/health/readiness ... '
ok=false
for _ in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:$BACKEND_PORT/api/v1/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; then ok=true; break; fi
  sleep 3
done
[ "$ok" = true ] || { echo; die "Backend khong UP: docker compose -p $PROJECT logs --tail 100 backend"; }
echo "UP"

# ================================================================= 10. smoke
step "Kiem tra sau cai dat (smoke test)"
code=""
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$FRONT_PORT/" || true)"
  [ "$code" != "200" ] || break
  sleep 2
done
[ "$code" = "200" ] || die "Frontend http://127.0.0.1:$FRONT_PORT/ tra ve $code"
info "Frontend 200 qua cong $FRONT_PORT"
code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$FRONT_PORT/api/v1/health/readiness" || true)"
[ "$code" = "200" ] || die "Proxy /api qua frontend tra ve $code"
info "Proxy /api qua frontend OK"
if [ "$RESTORE_DATA" = true ]; then
  mysql_q() { # SQL  (password comes from the container's own env, never from a command line)
    docker exec "$(dc ps -q mysql | head -n 1)" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B "$MYSQL_DATABASE" -e "$0"' "$1" 2>/dev/null | tr -d '\r'
  }
  fw="$(mysql_q 'select max(cast(version as unsigned)) from flyway_schema_history where success=1')"
  [ "$fw" = "$(conf_value "$CONF" FLYWAY_VERSION)" ] || die "Flyway V$fw khac bundle V$(conf_value "$CONF" FLYWAY_VERSION)"
  info "Flyway V$fw (khop bundle)"
  mismatch=0
  while IFS='=' read -r k want; do
    case "$k" in COUNT_*) ;; *) continue ;; esac
    t="${k#COUNT_}"; got="$(mysql_q "select count(*) from $t")"
    if [ "$got" = "$want" ]; then info "bang $t: $got dong (khop)"; else warn "bang $t: $got dong, bundle co $want"; mismatch=1; fi
  done < "$CONF"
  [ "$mismatch" -eq 0 ] || die "So dong khong khop sau khoi phuc."
fi
API="http://127.0.0.1:$BACKEND_PORT/api/v1"
if [ "$USE_DEMO" = true ] && [ "$RESTORE_DATA" = true ]; then
  tok="$(printf '{"email":"owner@classroom.local","password":"Password123!"}' \
    | curl -fsS -X POST "$API/auth/login" -H 'Content-Type: application/json' --data-binary @- 2>/dev/null \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' || true)"
  if [ -n "$tok" ]; then
    info "Dang nhap owner@classroom.local OK"
    n="$(curl -fsS "$API/classes?size=200" 2>/dev/null | grep -o '"slug"' | wc -l | tr -d ' ')"
    info "API /classes tra ve $n lop cong khai"
    cls="$(curl -fsS "$API/classes/slug/lop-demo-day-du" -H "Authorization: Bearer $tok" 2>/dev/null || true)"
    cid="$(printf '%s' "$cls" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -n 1)"
    if [ -n "$cid" ]; then
      info "Lop trung bay lop-demo-day-du: OK (id $cid)"
      for p in leaderboard blog-posts courses; do
        c="$(curl -s -o /dev/null -w '%{http_code}' "$API/classes/$cid/$p" -H "Authorization: Bearer $tok" || true)"
        info "  /classes/{id}/$p -> HTTP $c"
      done
    else
      warn "Khong thay lop lop-demo-day-du (co the khong co trong du lieu nay)."
    fi
  else
    warn "Khong dang nhap duoc owner@classroom.local (neu du lieu khong phai demo thi binh thuong)."
  fi
fi

# ================================================================= 11. summary
step "Hoan tat"
cat <<EOF

  =====================================================================
   CAI DAT XONG - project '$PROJECT' trong $DIR_ABS
  =====================================================================
   Web      : http://localhost:$FRONT_PORT$([ -z "$ORIGIN" ] || echo "   (cong khai: $ORIGIN)")
   API      : http://127.0.0.1:$BACKEND_PORT/api/v1/health/readiness
   MinIO    : console http://127.0.0.1:$(env_value MINIO_CONSOLE_PORT)  (tai khoan/mat khau trong $ENV_FILE)
   Bind     : $BIND_ADDR  $([ "$BIND_ADDR" = "127.0.0.1" ] && echo "(chi truy cap duoc tu chinh server; mo ra ngoai: ./install.sh ... --bind 0.0.0.0 hoac dat reverse proxy HTTPS)")
   Bi mat   : $ENV_FILE (chmod 600) - KHONG commit/chia se.

   Dieu khien (khong co mat khau tren dong lenh):
     $INFRA/scripts/server-up.sh status | logs [svc] | stop | start | restart | backup | update
   hoac: cd $INFRA && docker compose -p $PROJECT ${COMPOSE_FILES[*]} --env-file .env <lenh>

   Sao luu dinh ky : $INFRA/scripts/server-up.sh backup   (-> $DIR_ABS/backups/)
   Tao bundle moi  : (tren server nguon) infra/scripts/make-migration-bundle.sh [--encrypt]
EOF
if [ "$USE_DEMO" = true ]; then
  cat <<EOF

  !!! CANH BAO DEMO !!!
   Stack dang dung compose.demo.yaml: tai khoan co dinh (owner@classroom.local, staff@classroom.local,
   student.*@classroom.local, demo.hocvien*@example.com) co mat khau  Password123!  va thanh toan SANDBOX
   (chu lop tu xac nhan don). TUYET DOI khong mo ra Internet. De dung that: doi mat khau/xoa tai khoan demo
   (infra/scripts/secure-server-accounts.*), roi chay lai voi --no-demo.
EOF
elif [ "$RESTORE_DATA" = true ] && [ "$BUNDLE_DEMO" = "1" ]; then
  cat <<EOF

  Luu y: --no-demo da tat compose.demo.yaml, nhung DU LIEU khoi phuc van chua cac tai khoan demo
  (mat khau Password123!). Doi mat khau/vo hieu hoa chung truoc khi mo ra ngoai
  (infra/scripts/secure-server-accounts.*, docs/RUNBOOK.md).
EOF
fi
[ "$KEEP_EXTRACTED" = true ] && echo "
  Da giu ban giai nen (CHUA DU LIEU + BI MAT): $WORK  - xoa khi khong can: rm -rf '$WORK'"
exit 0
