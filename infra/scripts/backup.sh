#!/usr/bin/env bash
# R13-11(b) (NFR-04/HLD 5), hardened by R14-06/07/08: back up MySQL, MongoDB, Neo4j and MinIO of one
# Compose project into a timestamped folder, with a verifiable manifest (backup.json).
# Bash equivalent of backup.ps1 - see that file's header for the full behaviour notes.
#
# Usage:
#   infra/scripts/backup.sh [OUT_DIR] [options]
#
# Options:
#   --out-dir DIR            base directory for the timestamped folder (default: <infra>/backups)
#   --project-name NAME      Compose project to back up (default: `name:` of compose.yaml = live stack)
#   --infra-dir DIR          directory holding compose.yaml and .env (default: parent of this script)
#   --env-file FILE          env file for `docker compose --env-file` (repeatable; default <infra>/.env)
#   --overlay FILE           extra compose file (repeatable), e.g. infra/compose.drill.yaml
#   --mysql-container C | --mongo-container C | --neo4j-container C | --minio-container C
#                            explicit container name/id overriding the compose-project lookup
#   --bucket NAME            MinIO bucket (default: MINIO_BUCKET from the env file, else classroom-media)
#   --mc-image IMAGE         mc image for the MinIO mirror
#   --include-neo4j-dump     stop neo4j briefly and include its offline dump
#
# Every component must succeed: any failure exits non-zero, "Backup complete" is NOT printed and a
# backup.failed marker is left in the folder. Credentials are read from the target containers'
# environment and never appear on a command line.
set -euo pipefail

# shellcheck source=common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

OUT_DIR=""
PROJECT_NAME=""
INFRA_DIR=""
ENV_FILES=()
OVERLAYS=()
MYSQL_CONTAINER=""; MONGO_CONTAINER=""; NEO4J_CONTAINER=""; MINIO_CONTAINER=""
BUCKET=""
MC_IMAGE="$DEFAULT_MC_IMAGE"
INCLUDE_NEO4J_DUMP=false

while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir)            OUT_DIR="${2:?--out-dir needs a value}"; shift 2 ;;
    --project-name)       PROJECT_NAME="${2:?--project-name needs a value}"; shift 2 ;;
    --infra-dir)          INFRA_DIR="${2:?--infra-dir needs a value}"; shift 2 ;;
    --env-file)           ENV_FILES+=("${2:?--env-file needs a value}"); shift 2 ;;
    --overlay)            OVERLAYS+=("${2:?--overlay needs a value}"); shift 2 ;;
    --mysql-container)    MYSQL_CONTAINER="${2:?}"; shift 2 ;;
    --mongo-container)    MONGO_CONTAINER="${2:?}"; shift 2 ;;
    --neo4j-container)    NEO4J_CONTAINER="${2:?}"; shift 2 ;;
    --minio-container)    MINIO_CONTAINER="${2:?}"; shift 2 ;;
    --bucket)             BUCKET="${2:?--bucket needs a value}"; shift 2 ;;
    --mc-image)           MC_IMAGE="${2:?--mc-image needs a value}"; shift 2 ;;
    --include-neo4j-dump) INCLUDE_NEO4J_DUMP=true; shift ;;
    -h|--help)            sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)                   die "Unknown option: $1" ;;
    *)                    if [ -z "$OUT_DIR" ]; then OUT_DIR="$1"; shift; else die "Unexpected argument: $1"; fi ;;
  esac
done

BACKUP_DIR=""
NEO4J_TO_RESTART=""
COMPLETED=false

# Runs on every exit: restarts neo4j if we stopped it, and reports/marks a failed run.
on_exit() {
  local rc=$?
  if [ -n "$NEO4J_TO_RESTART" ]; then
    docker start "$NEO4J_TO_RESTART" >/dev/null 2>&1 || echo "WARNING: could not restart neo4j container $NEO4J_TO_RESTART - start it manually." >&2
    wait_healthy "$NEO4J_TO_RESTART" 180 || echo "WARNING: neo4j did not report healthy within 180s after restart." >&2
  fi
  if [ "$COMPLETED" != true ]; then
    [ "$rc" -ne 0 ] || rc=1
    echo "Backup FAILED (exit code $rc)." >&2
    if [ -n "$BACKUP_DIR" ] && [ -d "$BACKUP_DIR" ]; then
      echo "Backup failed (exit code $rc)" > "$BACKUP_DIR/backup.failed" 2>/dev/null || true
      echo "This folder is INCOMPLETE and has no backup.json; do not restore from it: $BACKUP_DIR" >&2
    fi
    exit "$rc"
  fi
}
trap on_exit EXIT

INFRA_DIR="$(abs_path "${INFRA_DIR:-$SCRIPT_DIR/..}")"
COMPOSE_FILE="$INFRA_DIR/compose.yaml"
[ -f "$COMPOSE_FILE" ] || die "compose.yaml not found in $INFRA_DIR"
if [ ${#ENV_FILES[@]} -eq 0 ]; then ENV_FILES=("$INFRA_DIR/.env"); fi
for i in "${!ENV_FILES[@]}"; do
  ENV_FILES[$i]="$(abs_path "${ENV_FILES[$i]}")"
  [ -f "${ENV_FILES[$i]}" ] || die "Env file not found: ${ENV_FILES[$i]} (copy .env.example and fill in credentials first)."
done
for i in ${OVERLAYS[@]+"${!OVERLAYS[@]}"}; do OVERLAYS[$i]="$(abs_path "${OVERLAYS[$i]}")"; done
[ -n "$PROJECT_NAME" ] || PROJECT_NAME="$(compose_file_project_name "$COMPOSE_FILE")"
OUT_DIR="$(abs_path "${OUT_DIR:-$INFRA_DIR/backups}")"

if [ -z "$BUCKET" ]; then
  for f in "${ENV_FILES[@]}"; do v="$(env_file_value "$f" MINIO_BUCKET)"; [ -z "$v" ] || BUCKET="$v"; done
fi
BUCKET="${BUCKET:-classroom-media}"

build_compose_args "$PROJECT_NAME" "$COMPOSE_FILE"

MYSQL_ID="$(service_container mysql "$MYSQL_CONTAINER")"
MONGO_ID="$(service_container mongodb "$MONGO_CONTAINER")"
MINIO_ID="$(service_container minio "$MINIO_CONTAINER")"
NEO4J_ID=""
if [ "$INCLUDE_NEO4J_DUMP" = true ]; then NEO4J_ID="$(service_container neo4j "$NEO4J_CONTAINER")"; fi

require_running "$MYSQL_ID" "MySQL"
require_running "$MONGO_ID" "MongoDB"
MYSQL_DATABASE="$(container_env "$MYSQL_ID" MYSQL_DATABASE)"
MONGO_DATABASE="$(container_env "$MONGO_ID" MONGO_INITDB_DATABASE)"
[ -n "$MYSQL_DATABASE" ] || die "MySQL container has no MYSQL_DATABASE in its environment."
[ -n "$MONGO_DATABASE" ] || die "MongoDB container has no MONGO_INITDB_DATABASE in its environment."

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$OUT_DIR/$TIMESTAMP"
mkdir -p "$BACKUP_DIR"
echo "Backup folder: $BACKUP_DIR"
echo "Compose project: $PROJECT_NAME (mysql=$(container_name "$MYSQL_ID"), mongodb=$(container_name "$MONGO_ID"))"

# ---------------------------------------------------------------------------
# 1. MySQL
# ---------------------------------------------------------------------------
step "Backing up MySQL ($MYSQL_DATABASE)..."
MYSQL_FILE="mysql-$MYSQL_DATABASE.sql"
MYSQL_HELPER="$(install_helper "$MYSQL_ID" "backup-$TIMESTAMP")"
MYSQL_TMP="/tmp/classroom-backup-$TIMESTAMP.sql"
rc=0
{ docker exec "$MYSQL_ID" sh "$MYSQL_HELPER" mysql-dump "$MYSQL_TMP" \
    && docker cp "$MYSQL_ID:$MYSQL_TMP" "$(host_path "$BACKUP_DIR/$MYSQL_FILE")"; } || rc=$?
remove_container_files "$MYSQL_ID" "$MYSQL_TMP" "$MYSQL_HELPER"
[ "$rc" -eq 0 ] || die "MySQL dump failed (exit code $rc)."
# mysqldump ends a complete dump with "-- Dump completed"; a truncated file must not be trusted.
tail -n 5 "$BACKUP_DIR/$MYSQL_FILE" | grep -q "Dump completed" || die "MySQL dump looks truncated (no 'Dump completed' trailer)."
add_file_component mysql "$BACKUP_DIR" "$MYSQL_FILE"
read -r size _ <<< "$(last_component_summary)"
info "-> $MYSQL_FILE ($size bytes)"

# ---------------------------------------------------------------------------
# 2. MongoDB
# ---------------------------------------------------------------------------
step "Backing up MongoDB ($MONGO_DATABASE)..."
MONGO_FILE="mongodb-$MONGO_DATABASE.archive.gz"
MONGO_HELPER="$(install_helper "$MONGO_ID" "backup-$TIMESTAMP")"
MONGO_TMP="/tmp/classroom-backup-$TIMESTAMP.archive.gz"
rc=0
{ docker exec "$MONGO_ID" sh "$MONGO_HELPER" mongo-dump "$MONGO_TMP" \
    && docker cp "$MONGO_ID:$MONGO_TMP" "$(host_path "$BACKUP_DIR/$MONGO_FILE")"; } || rc=$?
remove_container_files "$MONGO_ID" "$MONGO_TMP" "$MONGO_HELPER"
[ "$rc" -eq 0 ] || die "MongoDB dump failed (exit code $rc)."
add_file_component mongodb "$BACKUP_DIR" "$MONGO_FILE"
read -r size _ <<< "$(last_component_summary)"
info "-> $MONGO_FILE ($size bytes)"

# ---------------------------------------------------------------------------
# 3. Neo4j - neo4j-admin database dump REQUIRES the database to be stopped (R14-06)
# ---------------------------------------------------------------------------
if [ "$INCLUDE_NEO4J_DUMP" = true ]; then
  step "Backing up Neo4j (stopping the container for an offline dump)..."
  if container_running "$NEO4J_ID"; then
    docker stop "$NEO4J_ID" >/dev/null
    NEO4J_TO_RESTART="$NEO4J_ID"   # restarted by on_exit whatever happens next
  fi
  # ONE run (the old script dumped twice and discarded the first result), writing straight into the
  # backup folder through a bind mount; the scratch folder is world-writable because the image
  # drops privileges to the neo4j user.
  SCRATCH="$BACKUP_DIR/.neo4j-dump"
  mkdir -p "$SCRATCH"
  chmod 0777 "$SCRATCH"
  neo4j_admin "$NEO4J_ID" "$SCRATCH" 0 database dump neo4j --to-path=/backups
  [ -f "$SCRATCH/neo4j.dump" ] || die "neo4j-admin reported success but produced no neo4j.dump."
  mv "$SCRATCH/neo4j.dump" "$BACKUP_DIR/neo4j.dump"
  rmdir "$SCRATCH"
  if [ -n "$NEO4J_TO_RESTART" ]; then
    docker start "$NEO4J_TO_RESTART" >/dev/null
    wait_healthy "$NEO4J_TO_RESTART" 180 || warn "neo4j did not report healthy within 180s after restart."
    NEO4J_TO_RESTART=""
  fi
  add_file_component neo4j "$BACKUP_DIR" "neo4j.dump"
  read -r size _ <<< "$(last_component_summary)"
  info "-> neo4j.dump ($size bytes)"
else
  step "Skipping Neo4j dump (neo4j-admin database dump requires the database to be stopped)."
  info "Pass --include-neo4j-dump to stop/dump/restart it here, or see docs/RUNBOOK.md section 5.4."
fi

# ---------------------------------------------------------------------------
# 4. MinIO - any mc failure fails the whole backup (no `|| echo` swallow any more)
# ---------------------------------------------------------------------------
step "Backing up MinIO bucket ($BUCKET)..."
MINIO_DIR_NAME="minio-$BUCKET"
mkdir -p "$BACKUP_DIR/$MINIO_DIR_NAME"
run_mc "$MINIO_ID" "$MC_IMAGE" "$BACKUP_DIR/$MINIO_DIR_NAME" 0 mirror --overwrite "myminio/$BUCKET" /backup
add_tree_component minio "$BACKUP_DIR" "$MINIO_DIR_NAME"
read -r size files <<< "$(last_component_summary)"
info "-> $MINIO_DIR_NAME ($files files, $size bytes)"

# ---------------------------------------------------------------------------
# 5. Manifest + self-check
# ---------------------------------------------------------------------------
step "Writing and verifying $MANIFEST_NAME..."
write_manifest "$BACKUP_DIR" "$PROJECT_NAME" "$TIMESTAMP"
verify_manifest "$BACKUP_DIR"

COMPLETED=true
echo ""
echo "Backup complete: $BACKUP_DIR"
echo "See docs/RUNBOOK.md section 5 for the restore drill."
