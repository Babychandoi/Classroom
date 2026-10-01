#!/usr/bin/env bash
# common.sh - helpers shared by backup.sh and restore.sh (sourced; not meant to be run directly).
# Bash counterpart of common.ps1; the two families read each other's backups (same backup.json,
# same tree digest). See common.ps1 / infra/scripts/container/dbtool.sh for the design notes:
#  - containers are resolved through the Compose PROJECT, never a hard-coded container_name;
#  - credentials come from the target containers' own environment and never touch a command line.

# Git Bash / MSYS would otherwise rewrite container paths such as /tmp/x into Windows paths.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_MC_IMAGE="quay.io/minio/mc:RELEASE.2024-05-09T17-04-24Z"
MANIFEST_NAME="backup.json"

step() { printf '==> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf '%s\n' "$*" >&2; exit 1; }

# Path spelling the docker CLI understands (Windows-style under Git Bash/MSYS, unchanged elsewhere).
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# Absolute path of an existing directory or file, or of a not-yet-existing path in an existing dir.
abs_path() {
  local p="$1"
  if [ -d "$p" ]; then (cd "$p" && pwd); return; fi
  case "$p" in
    /*|[A-Za-z]:*) printf '%s' "$p" ;;
    *) printf '%s/%s' "$PWD" "$p" ;;
  esac
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
sha256_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1
  else shasum -a 256 | cut -d' ' -f1; fi
}
file_size() { wc -c < "$1" | tr -d ' '; }

# Canonical tree digest, identical to Get-TreeDigest in common.ps1: sha256 over the text of lines
# "<file sha256>  <relative path>\n" sorted by relative path in byte order.
# Prints: "<files> <bytes> <sha256>"
tree_digest() {
  local dir="$1" list f h s files=0 bytes=0 text=""
  list="$(cd "$dir" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)"
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    h="$(sha256_file "$dir/$f")"
    s="$(file_size "$dir/$f")"
    text+="$h  $f"$'\n'
    files=$((files + 1))
    bytes=$((bytes + s))
  done <<< "$list"
  printf '%s %s %s\n' "$files" "$bytes" "$(printf '%s' "$text" | sha256_stdin)"
}

json_str() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '"%s"' "$s"
}

url_encode() {
  local LC_ALL=C s="$1" out="" c i
  for ((i = 0; i < ${#s}; i++)); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) out+="$c" ;;
      *) out+="$(printf '%%%02X' "'$c")" ;;
    esac
  done
  printf '%s' "$out"
}

# Value of KEY from a KEY=VALUE env file (quotes stripped); empty when absent.
env_file_value() {
  local file="$1" key="$2" line v
  [ -f "$file" ] || return 0
  line="$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=" "$file" | tail -n 1 || true)"
  [ -n "$line" ] || return 0
  v="${line#*=}"
  v="$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  case "$v" in
    \"*\") v="${v#\"}"; v="${v%\"}" ;;
    \'*\') v="${v#\'}"; v="${v%\'}" ;;
  esac
  printf '%s' "$v"
}

compose_file_project_name() {
  local name
  name="$(sed -n 's/^name:[[:space:]]*\([^[:space:]]*\).*$/\1/p' "$1" | head -n 1)"
  printf '%s' "${name:-online-classroom}"
}

# ---------------------------------------------------------------------------
# Docker helpers. COMPOSE_ARGS must be set by the caller (see build_compose_args).
# ---------------------------------------------------------------------------
build_compose_args() { # project compose_file ; uses arrays OVERLAYS and ENV_FILES
  COMPOSE_ARGS=(compose -p "$1" -f "$(host_path "$2")")
  local f
  for f in ${OVERLAYS[@]+"${OVERLAYS[@]}"}; do COMPOSE_ARGS+=(-f "$(host_path "$f")"); done
  for f in ${ENV_FILES[@]+"${ENV_FILES[@]}"}; do COMPOSE_ARGS+=(--env-file "$(host_path "$f")"); done
}

# service_container SERVICE [OVERRIDE] [optional]  -> prints the container id
service_container() {
  local service="$1" override="${2:-}" optional="${3:-}" ids count
  if [ -n "$override" ]; then
    docker inspect --format '{{.Id}}' "$override"
    return
  fi
  ids="$(docker "${COMPOSE_ARGS[@]}" ps -a -q "$service")"
  count="$(printf '%s\n' "$ids" | grep -c . || true)"
  if [ "$count" -eq 0 ]; then
    [ -n "$optional" ] && return 0
    die "No container found for service '$service' in the target compose project. Is the stack up?"
  fi
  [ "$count" -eq 1 ] || die "More than one container found for service '$service'; pass an explicit container override."
  printf '%s\n' "$ids" | head -n 1
}

container_name()    { docker inspect --format '{{.Name}}' "$1" | sed 's|^/||'; }
container_running() { [ "$(docker inspect --format '{{.State.Running}}' "$1")" = "true" ]; }
container_env() {   # container_id VAR_NAME
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$1" | sed -n "s/^$2=//p" | head -n 1
}
require_running() { container_running "$1" || die "$2 container '$(container_name "$1")' is not running."; }

wait_healthy() { # container_id timeout_seconds
  local cid="$1" timeout="${2:-180}" waited=0 running health
  while [ "$waited" -lt "$timeout" ]; do
    running="$(docker inspect --format '{{.State.Running}}' "$cid")"
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid")"
    if [ "$running" = "true" ] && { [ "$health" = "healthy" ] || [ "$health" = "none" ]; }; then return 0; fi
    sleep 3
    waited=$((waited + 3))
  done
  return 1
}

# In-container helper (infra/scripts/container/dbtool.sh); prints the path it was copied to.
install_helper() { # container_id suffix
  local cid="$1" target="/tmp/classroom-dbtool-$2.sh" tmp
  tmp="$(mktemp)"
  tr -d '\r' < "$SCRIPT_DIR/container/dbtool.sh" > "$tmp"
  if ! docker cp "$(host_path "$tmp")" "$cid:$target" >/dev/null; then rm -f "$tmp"; return 1; fi
  rm -f "$tmp"
  printf '%s' "$target"
}
remove_container_files() { # container_id paths...  (best effort)
  local cid="$1"; shift
  docker exec "$cid" rm -f "$@" >/dev/null 2>&1 || true
}

# Neo4j offline dump/load in a throw-away container on the service's own /data volume that
# bind-mounts a HOST directory at /backups (R14-06).  neo4j_admin CID HOST_DIR READONLY(0|1) ARGS...
neo4j_admin() {
  local cid="$1" dir="$2" ro="$3" image source spec
  shift 3
  image="$(docker inspect --format '{{.Config.Image}}' "$cid")"
  source="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{if .Name}}{{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}' "$cid")"
  [ -n "$source" ] || die "Neo4j container '$(container_name "$cid")' has no /data mount."
  spec="$(host_path "$dir"):/backups"
  [ "$ro" = "1" ] && spec="$spec:ro"
  docker run --rm -v "$source:/data" -v "$spec" "$image" neo4j-admin "$@"
}

# One-off mc container on the MinIO container's network. Credentials travel through the process
# environment (-e NAME copies it), never on the docker command line.
#   run_mc MINIO_CID MC_IMAGE HOST_DIR|"" READONLY(0|1) MC_ARGS...
run_mc() {
  local cid="$1" image="$2" dir="$3" ro="$4" user pass network spec
  shift 4
  require_running "$cid" "MinIO"
  user="$(container_env "$cid" MINIO_ROOT_USER)"
  pass="$(container_env "$cid" MINIO_ROOT_PASSWORD)"
  [ -n "$user" ] && [ -n "$pass" ] || die "MinIO container does not expose MINIO_ROOT_USER/MINIO_ROOT_PASSWORD."
  network="$(docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$cid" | awk '{print $1}')"
  [ -n "$network" ] || die "Cannot determine the MinIO container's network."
  if [ -n "$dir" ]; then
    spec="$(host_path "$dir"):/backup"
    [ "$ro" = "1" ] && spec="$spec:ro"
    MC_HOST_myminio="http://$(url_encode "$user"):$(url_encode "$pass")@minio:9000" \
      docker run --rm --network "$network" -e MC_HOST_myminio -v "$spec" "$image" "$@"
  else
    MC_HOST_myminio="http://$(url_encode "$user"):$(url_encode "$pass")@minio:9000" \
      docker run --rm --network "$network" -e MC_HOST_myminio "$image" "$@"
  fi
}

# ---------------------------------------------------------------------------
# Manifest (backup.json) - one component per line so it can be read with sed, no jq needed.
# ---------------------------------------------------------------------------
COMPONENT_LINES=()

add_file_component() { # name backup_dir relative_path
  local full="$2/$3" size
  [ -f "$full" ] || die "Component '$1' produced no file at $full"
  size="$(file_size "$full")"
  [ "$size" -gt 0 ] || die "Component '$1' file is empty: $full"
  COMPONENT_LINES+=("$(printf '{"name": %s, "type": "file", "path": %s, "bytes": %s, "files": 1, "sha256": "%s"}' \
    "$(json_str "$1")" "$(json_str "$3")" "$size" "$(sha256_file "$full")")")
}
add_tree_component() { # name backup_dir relative_path
  local full="$2/$3" files bytes digest
  [ -d "$full" ] || die "Component '$1' produced no directory at $full"
  read -r files bytes digest <<< "$(tree_digest "$full")"
  COMPONENT_LINES+=("$(printf '{"name": %s, "type": "tree", "path": %s, "bytes": %s, "files": %s, "sha256": "%s"}' \
    "$(json_str "$1")" "$(json_str "$3")" "$bytes" "$files" "$digest")")
}
last_component_summary() { # prints "bytes files" of the most recent component
  printf '%s' "${COMPONENT_LINES[${#COMPONENT_LINES[@]} - 1]}" \
    | sed -n 's/.*"bytes": \([0-9]*\), "files": \([0-9]*\),.*/\1 \2/p'
}

write_manifest() { # backup_dir project timestamp
  local out="$1/$MANIFEST_NAME" i last=$((${#COMPONENT_LINES[@]} - 1))
  {
    printf '{\n'
    printf '  "version": 1,\n'
    printf '  "timestamp": %s,\n' "$(json_str "$3")"
    printf '  "createdUtc": %s,\n' "$(json_str "$(date -u +%Y-%m-%dT%H:%M:%SZ)")"
    printf '  "project": %s,\n' "$(json_str "$2")"
    printf '  "components": [\n'
    for i in "${!COMPONENT_LINES[@]}"; do
      if [ "$i" -lt "$last" ]; then printf '    %s,\n' "${COMPONENT_LINES[$i]}"; else printf '    %s\n' "${COMPONENT_LINES[$i]}"; fi
    done
    printf '  ]\n}\n'
  } > "$out"
}

# manifest_components FILE -> lines "name|type|path|bytes|files|sha256"
manifest_components() {
  sed -n 's/^ *{"name": "\([^"]*\)", "type": "\([^"]*\)", "path": "\([^"]*\)", "bytes": \([0-9]*\), "files": \([0-9]*\), "sha256": "\([0-9a-f]*\)"},\{0,1\} *$/\1|\2|\3|\4|\5|\6/p' "$1"
}

# Verifies every component (size + sha256, or tree digest) against the files on disk.
# Reads only. Exits the script (die) on the first problem.
verify_manifest() { # backup_dir
  local dir="$1" manifest="$1/$MANIFEST_NAME" name type path bytes files sha full got_size got_sha got_files got_bytes count=0
  [ -f "$manifest" ] || die "No $MANIFEST_NAME in $dir - not a complete backup made by backup.ps1/backup.sh (use --skip-manifest-verify only for a legacy backup you trust)."
  grep -q '"version": 1' "$manifest" || die "Unsupported manifest version in $manifest."
  while IFS='|' read -r name type path bytes files sha; do
    [ -n "$name" ] || continue
    full="$dir/$path"
    if [ "$type" = "file" ]; then
      [ -f "$full" ] || die "Component '$name': file missing: $path"
      got_size="$(file_size "$full")"
      [ "$got_size" = "$bytes" ] || die "Component '$name': size $got_size != manifest $bytes ($path)"
      got_sha="$(sha256_file "$full")"
      [ "$got_sha" = "$sha" ] || die "Component '$name': sha256 mismatch for $path"
    elif [ "$type" = "tree" ]; then
      [ -d "$full" ] || die "Component '$name': directory missing: $path"
      read -r got_files got_bytes got_sha <<< "$(tree_digest "$full")"
      { [ "$got_files" = "$files" ] && [ "$got_bytes" = "$bytes" ] && [ "$got_sha" = "$sha" ]; } \
        || die "Component '$name': directory content differs from manifest ($path)"
    else
      die "Component '$name': unknown type '$type'"
    fi
    info "$(printf 'verified %-8s %s (%s bytes, sha256 %s...)' "$name" "$path" "$bytes" "${sha:0:12}")"
    count=$((count + 1))
  done < <(manifest_components "$manifest")
  [ "$count" -gt 0 ] || die "Manifest lists no components (or is not in the expected one-component-per-line format)."
}
