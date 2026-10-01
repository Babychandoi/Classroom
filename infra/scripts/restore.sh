#!/usr/bin/env bash
# R13-11(b) (NFR-04/HLD 5), hardened by R14-06/07/08: restore MySQL, MongoDB, Neo4j and MinIO of ONE
# explicitly named Compose project from a backup folder made by backup.sh / backup.ps1.
# Bash equivalent of restore.ps1 - see that file's header for the full behaviour notes.
#
# DESTRUCTIVE. The target is never implicit and nothing is hard-coded to the live stack:
#   * --project-name is mandatory (e.g. classroom-drill, or online-classroom for the live stack);
#   * the run REFUSES to start unless --yes is given AND the project name is confirmed: typed at the
#     prompt (interactive) or passed as --confirm-project NAME (non-interactive), matching exactly;
#   * the backup is verified against backup.json (size + sha256 of every component) BEFORE anything is
#     touched; --verify-only stops after that check (needs no --yes and no running stack);
#   * the backend (datastore writer) is stopped before MySQL/MongoDB/MinIO are overwritten and started
#     again afterwards; after a FAILED restore it is left stopped on purpose and the exit code is non-zero;
#   * MySQL is a full replace (all tables of the app database dropped first), MongoDB uses
#     mongorestore --drop, MinIO objects are copied over the bucket - objects created AFTER the backup
#     survive unless --mirror-remove is given (mc mirror --remove: the bucket then equals the backup).
#
# Usage:
#   infra/scripts/restore.sh BACKUP_DIR --project-name NAME --yes [--confirm-project NAME] [options]
#   infra/scripts/restore.sh BACKUP_DIR --verify-only
#
# Options:
#   --infra-dir DIR | --env-file FILE (repeatable) | --overlay FILE (repeatable, e.g. infra/compose.drill.yaml)
#   --mysql-container C | --mongo-container C | --neo4j-container C | --minio-container C | --backend-container C
#   --bucket NAME        MinIO bucket to restore into (default: MINIO_BUCKET from the env file, else classroom-media)
#   --mc-image IMAGE
#   --include-neo4j      also restore neo4j.dump (neo4j is stopped for the load, then started again)
#   --mirror-remove      delete bucket objects that are not in the backup
#   --skip-manifest-verify   restore a legacy backup with no backup.json (integrity NOT checked)
#   --keep-writers-stopped   do not start the backend again after a successful restore
set -euo pipefail

# shellcheck source=common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

BACKUP_DIR=""
PROJECT_NAME=""
ASSUME_YES=false
CONFIRM_PROJECT=""
INFRA_DIR=""
ENV_FILES=()
OVERLAYS=()
MYSQL_CONTAINER=""; MONGO_CONTAINER=""; NEO4J_CONTAINER=""; MINIO_CONTAINER=""; BACKEND_CONTAINER=""
BUCKET=""
MC_IMAGE="$DEFAULT_MC_IMAGE"
INCLUDE_NEO4J=false
MIRROR_REMOVE=false
VERIFY_ONLY=false
SKIP_MANIFEST_VERIFY=false
KEEP_WRITERS_STOPPED=false

while [ $# -gt 0 ]; do
  case "$1" in
    --project-name)          PROJECT_NAME="${2:?--project-name needs a value}"; shift 2 ;;
    --yes|--force)           ASSUME_YES=true; shift ;;
    --confirm-project)       CONFIRM_PROJECT="${2:?--confirm-project needs a value}"; shift 2 ;;
    --infra-dir)             INFRA_DIR="${2:?--infra-dir needs a value}"; shift 2 ;;
    --env-file)              ENV_FILES+=("${2:?--env-file needs a value}"); shift 2 ;;
    --overlay)               OVERLAYS+=("${2:?--overlay needs a value}"); shift 2 ;;
    --mysql-container)       MYSQL_CONTAINER="${2:?}"; shift 2 ;;
    --mongo-container)       MONGO_CONTAINER="${2:?}"; shift 2 ;;
    --neo4j-container)       NEO4J_CONTAINER="${2:?}"; shift 2 ;;
    --minio-container)       MINIO_CONTAINER="${2:?}"; shift 2 ;;
    --backend-container)     BACKEND_CONTAINER="${2:?}"; shift 2 ;;
    --bucket)                BUCKET="${2:?--bucket needs a value}"; shift 2 ;;
    --mc-image)              MC_IMAGE="${2:?--mc-image needs a value}"; shift 2 ;;
    --include-neo4j)         INCLUDE_NEO4J=true; shift ;;
    --mirror-remove)         MIRROR_REMOVE=true; shift ;;
    --verify-only)           VERIFY_ONLY=true; shift ;;
    --skip-manifest-verify)  SKIP_MANIFEST_VERIFY=true; shift ;;
    --keep-writers-stopped)  KEEP_WRITERS_STOPPED=true; shift ;;
    -h|--help)               sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)                      die "Unknown option: $1" ;;
    *)                       if [ -z "$BACKUP_DIR" ]; then BACKUP_DIR="$1"; shift; else die "Unexpected argument: $1"; fi ;;
  esac
done
[ -n "$BACKUP_DIR" ] || die "Usage: $0 BACKUP_DIR --project-name NAME --yes [--confirm-project NAME] [options]   (or: $0 BACKUP_DIR --verify-only)"

STOPPED_WRITERS=()
RESTORE_STARTED=false
NEO4J_TO_RESTART=""
COMPLETED=false

on_exit() {
  local rc=$?
  if [ -n "$NEO4J_TO_RESTART" ]; then
    docker start "$NEO4J_TO_RESTART" >/dev/null 2>&1 || echo "WARNING: could not restart neo4j container $NEO4J_TO_RESTART - start it manually." >&2
    wait_healthy "$NEO4J_TO_RESTART" 180 || echo "WARNING: neo4j did not report healthy within 180s after restart." >&2
  fi
  if [ "$COMPLETED" != true ]; then
    [ "$rc" -ne 0 ] || rc=1
    echo "Restore FAILED (exit code $rc)." >&2
    if [ "$RESTORE_STARTED" = true ] && [ ${#STOPPED_WRITERS[@]} -gt 0 ]; then
      echo "The datastore writers were left STOPPED on purpose (data may be half-restored). Fix the problem and re-run," >&2
      echo "or start them manually once you have checked the data: docker start ${STOPPED_WRITERS[*]}" >&2
    fi
    exit "$rc"
  fi
}
trap on_exit EXIT

[ -d "$BACKUP_DIR" ] || die "Backup folder not found: $BACKUP_DIR"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"

# ------------------------------------------------------------------ 1. verify the backup first
step "Verifying backup $BACKUP_DIR"
HAVE_MANIFEST=false
if [ "$SKIP_MANIFEST_VERIFY" = true ]; then
  warn "Manifest verification skipped (--skip-manifest-verify): the backup's integrity is NOT checked."
else
  verify_manifest "$BACKUP_DIR"
  HAVE_MANIFEST=true
  info "manifest OK: project '$(sed -n 's/^ *"project": "\([^"]*\)".*$/\1/p' "$BACKUP_DIR/$MANIFEST_NAME")', created $(sed -n 's/^ *"createdUtc": "\([^"]*\)".*$/\1/p' "$BACKUP_DIR/$MANIFEST_NAME")"
fi

# Component locations: from the manifest when present, otherwise by file name.
MYSQL_DUMP=""; MONGO_ARCHIVE=""; NEO4J_DUMP=""; MINIO_TREE=""
if [ "$HAVE_MANIFEST" = true ]; then
  while IFS='|' read -r name type path _ _ _; do
    case "$name" in
      mysql)   MYSQL_DUMP="$BACKUP_DIR/$path" ;;
      mongodb) MONGO_ARCHIVE="$BACKUP_DIR/$path" ;;
      neo4j)   NEO4J_DUMP="$BACKUP_DIR/$path" ;;
      minio)   MINIO_TREE="$BACKUP_DIR/$path" ;;
    esac
  done < <(manifest_components "$BACKUP_DIR/$MANIFEST_NAME")
else
  for f in "$BACKUP_DIR"/mysql-*.sql; do [ -f "$f" ] && MYSQL_DUMP="$f" && break; done
  for f in "$BACKUP_DIR"/mongodb-*.archive.gz; do [ -f "$f" ] && MONGO_ARCHIVE="$f" && break; done
  for f in "$BACKUP_DIR"/minio-*; do [ -d "$f" ] && MINIO_TREE="$f" && break; done
  [ -f "$BACKUP_DIR/neo4j.dump" ] && NEO4J_DUMP="$BACKUP_DIR/neo4j.dump"
fi
if [ "$INCLUDE_NEO4J" = true ] && [ -z "$NEO4J_DUMP" ]; then
  die "--include-neo4j given but this backup has no neo4j.dump (it was made without --include-neo4j-dump)."
fi

if [ "$VERIFY_ONLY" = true ]; then
  COMPLETED=true
  echo ""
  echo "Verify-only: backup is intact. Nothing was changed."
  exit 0
fi

# ------------------------------------------------------------------ 2. resolve the target
[ -n "$PROJECT_NAME" ] || die "--project-name is required: name the Compose project to overwrite (e.g. classroom-drill). There is no default on purpose."
INFRA_DIR="$(abs_path "${INFRA_DIR:-$SCRIPT_DIR/..}")"
COMPOSE_FILE="$INFRA_DIR/compose.yaml"
[ -f "$COMPOSE_FILE" ] || die "compose.yaml not found in $INFRA_DIR"
if [ ${#ENV_FILES[@]} -eq 0 ]; then ENV_FILES=("$INFRA_DIR/.env"); fi
for i in "${!ENV_FILES[@]}"; do
  ENV_FILES[$i]="$(abs_path "${ENV_FILES[$i]}")"
  [ -f "${ENV_FILES[$i]}" ] || die "Env file not found: ${ENV_FILES[$i]}"
done
for i in ${OVERLAYS[@]+"${!OVERLAYS[@]}"}; do OVERLAYS[$i]="$(abs_path "${OVERLAYS[$i]}")"; done
if [ -z "$BUCKET" ]; then
  for f in "${ENV_FILES[@]}"; do v="$(env_file_value "$f" MINIO_BUCKET)"; [ -z "$v" ] || BUCKET="$v"; done
fi
BUCKET="${BUCKET:-classroom-media}"

build_compose_args "$PROJECT_NAME" "$COMPOSE_FILE"
MYSQL_ID="$(service_container mysql "$MYSQL_CONTAINER")"
MONGO_ID="$(service_container mongodb "$MONGO_CONTAINER")"
MINIO_ID="$(service_container minio "$MINIO_CONTAINER")"
NEO4J_ID=""
if [ "$INCLUDE_NEO4J" = true ]; then NEO4J_ID="$(service_container neo4j "$NEO4J_CONTAINER")"; fi
require_running "$MYSQL_ID" "MySQL"
require_running "$MONGO_ID" "MongoDB"
require_running "$MINIO_ID" "MinIO"

echo ""
echo "RESTORE TARGET"
echo "  compose project : $PROJECT_NAME"
echo "  mysql           : $(container_name "$MYSQL_ID")"
echo "  mongodb         : $(container_name "$MONGO_ID")"
if [ "$MIRROR_REMOVE" = true ]; then
  echo "  minio           : $(container_name "$MINIO_ID") (bucket $BUCKET, objects not in the backup will be DELETED)"
else
  echo "  minio           : $(container_name "$MINIO_ID") (bucket $BUCKET)"
fi
if [ "$INCLUDE_NEO4J" = true ]; then echo "  neo4j           : $(container_name "$NEO4J_ID")"; fi
echo "  backup          : $BACKUP_DIR"
echo "This OVERWRITES the data of that project."

# ------------------------------------------------------------------ 3. explicit confirmation
[ "$ASSUME_YES" = true ] || die "Refusing to restore: pass --yes to acknowledge that project '$PROJECT_NAME' will be overwritten."
if [ -n "$CONFIRM_PROJECT" ]; then
  [ "$CONFIRM_PROJECT" = "$PROJECT_NAME" ] || die "--confirm-project '$CONFIRM_PROJECT' does not match --project-name '$PROJECT_NAME'."
elif [ -t 0 ]; then
  read -r -p "Type the compose project name '$PROJECT_NAME' to confirm: " typed
  [ "$typed" = "$PROJECT_NAME" ] || die "Confirmation did not match '$PROJECT_NAME'; nothing was changed."
else
  die "No interactive terminal to confirm on: pass --confirm-project $PROJECT_NAME to confirm non-interactively."
fi

# ------------------------------------------------------------------ 4. quiesce writers
RESTORE_STARTED=true
step "Stopping datastore writers (backend)..."
WRITER_ID="$(service_container backend "$BACKEND_CONTAINER" optional)"
if [ -n "$WRITER_ID" ] && container_running "$WRITER_ID"; then
  docker stop "$WRITER_ID" >/dev/null
  STOPPED_WRITERS+=("$WRITER_ID")
fi

STAMP="$(date +%Y%m%d-%H%M%S)"

# ------------------------------------------------------------------ 5. MySQL
if [ -n "$MYSQL_DUMP" ]; then
  step "Restoring MySQL from $MYSQL_DUMP..."
  tail -n 5 "$MYSQL_DUMP" | grep -q "Dump completed" || die "MySQL dump looks truncated (no 'Dump completed' trailer); not restoring."
  HELPER="$(install_helper "$MYSQL_ID" "restore-$STAMP")"
  TMP="/tmp/classroom-restore-$STAMP.sql"
  rc=0
  { docker cp "$(host_path "$MYSQL_DUMP")" "$MYSQL_ID:$TMP" \
      && docker exec "$MYSQL_ID" sh "$HELPER" mysql-restore "$TMP"; } || rc=$?
  remove_container_files "$MYSQL_ID" "$TMP" "$HELPER"
  [ "$rc" -eq 0 ] || die "MySQL restore failed (exit code $rc)."
  info "MySQL restore complete."
else
  warn "No MySQL dump in this backup - skipping."
fi

# ------------------------------------------------------------------ 6. MongoDB
if [ -n "$MONGO_ARCHIVE" ]; then
  step "Restoring MongoDB from $MONGO_ARCHIVE..."
  HELPER="$(install_helper "$MONGO_ID" "restore-$STAMP")"
  TMP="/tmp/classroom-restore-$STAMP.archive.gz"
  rc=0
  { docker cp "$(host_path "$MONGO_ARCHIVE")" "$MONGO_ID:$TMP" \
      && docker exec "$MONGO_ID" sh "$HELPER" mongo-restore "$TMP"; } || rc=$?
  remove_container_files "$MONGO_ID" "$TMP" "$HELPER"
  [ "$rc" -eq 0 ] || die "MongoDB restore failed (exit code $rc)."
  info "MongoDB restore complete."
else
  warn "No MongoDB archive in this backup - skipping."
fi

# ------------------------------------------------------------------ 7. Neo4j (offline load, R14-06)
if [ "$INCLUDE_NEO4J" = true ]; then
  step "Restoring Neo4j from $NEO4J_DUMP (stopping the container for an offline load)..."
  [ "$(basename "$NEO4J_DUMP")" = "neo4j.dump" ] || die "The Neo4j dump must be named neo4j.dump."
  if container_running "$NEO4J_ID"; then
    docker stop "$NEO4J_ID" >/dev/null
    NEO4J_TO_RESTART="$NEO4J_ID"
  fi
  # The load runs in a NEW container on the same data volume that bind-mounts the folder holding the
  # dump (a docker cp into the stopped service container would not be visible to a fresh container).
  neo4j_admin "$NEO4J_ID" "$(dirname "$NEO4J_DUMP")" 1 database load neo4j --from-path=/backups --overwrite-destination=true
  if [ -n "$NEO4J_TO_RESTART" ]; then
    docker start "$NEO4J_TO_RESTART" >/dev/null
    wait_healthy "$NEO4J_TO_RESTART" 180 || warn "neo4j did not report healthy within 180s after restart."
    NEO4J_TO_RESTART=""
  fi
  info "Neo4j restore complete."
else
  step "Skipping Neo4j restore (pass --include-neo4j to restore it; see docs/RUNBOOK.md)."
fi

# ------------------------------------------------------------------ 8. MinIO
if [ -n "$MINIO_TREE" ]; then
  step "Restoring MinIO bucket $BUCKET from $MINIO_TREE..."
  run_mc "$MINIO_ID" "$MC_IMAGE" "" 0 mb --ignore-existing "myminio/$BUCKET"
  MIRROR_ARGS=(mirror --overwrite)
  if [ "$MIRROR_REMOVE" = true ]; then MIRROR_ARGS+=(--remove); fi
  run_mc "$MINIO_ID" "$MC_IMAGE" "$MINIO_TREE" 1 "${MIRROR_ARGS[@]}" /backup "myminio/$BUCKET"
  info "MinIO restore complete."
else
  warn "No MinIO folder in this backup - skipping."
fi

# ------------------------------------------------------------------ 9. start writers again
if [ "$KEEP_WRITERS_STOPPED" = true ]; then
  step "Leaving the writers stopped (--keep-writers-stopped)."
  for id in ${STOPPED_WRITERS[@]+"${STOPPED_WRITERS[@]}"}; do info "start later with: docker start $id"; done
else
  step "Starting datastore writers again..."
  for id in ${STOPPED_WRITERS[@]+"${STOPPED_WRITERS[@]}"}; do
    docker start "$id" >/dev/null
    if wait_healthy "$id" 180; then info "healthy: $id"; else warn "container $id did not report healthy within 180s; check its logs."; fi
  done
fi

COMPLETED=true
echo ""
echo "Restore complete for project '$PROJECT_NAME'."
