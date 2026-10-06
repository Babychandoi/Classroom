#!/usr/bin/env bash
# server-up.sh - shortcuts around `docker compose` for a stack installed by install.sh.
# Reads infra/.server.conf (PROJECT, DEMO, OVERLAYS) so the same project/compose files are always used.
#
#   infra/scripts/server-up.sh start|stop|restart|status|logs [service]|backup|update|compose <args...>
#
#   start    docker compose up -d            (no rebuild)
#   stop     docker compose stop             (containers and volumes are kept)
#   restart  docker compose restart
#   status   docker compose ps + readiness probe
#   logs     docker compose logs --tail 200 -f [service]
#   backup   backup.sh incl. Neo4j dump -> <install dir>/backups/<timestamp>
#   update   rebuild backend + frontend from the current source and restart them (data untouched)
#   compose  any other docker compose subcommand with the right project/files
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="$INFRA_DIR/.server.conf"
PROJECT="classroom-demo"; DEMO=1; OVERLAYS=""
if [ -f "$CONF" ]; then
  while IFS='=' read -r k v; do
    case "$k" in PROJECT) PROJECT="$v" ;; DEMO) DEMO="$v" ;; OVERLAYS) OVERLAYS="$v" ;; esac
  done < <(grep -E '^(PROJECT|DEMO|OVERLAYS)=' "$CONF")
fi

FILES=(-f compose.yaml)
[ "$DEMO" != "1" ] || FILES+=(-f compose.demo.yaml)
BACKUP_OVERLAYS=()
[ "$DEMO" != "1" ] || BACKUP_OVERLAYS+=(--overlay "$INFRA_DIR/compose.demo.yaml")
for o in $OVERLAYS; do FILES+=(-f "$o"); BACKUP_OVERLAYS+=(--overlay "$o"); done

dc() { ( cd "$INFRA_DIR" && docker compose -p "$PROJECT" "${FILES[@]}" --env-file .env "$@" ); }

cmd="${1:-status}"; [ $# -eq 0 ] || shift
case "$cmd" in
  start)   dc up -d ;;
  stop)    dc stop ;;
  restart) dc restart "$@" ;;
  status)
    dc ps
    port="$(sed -n 's/^BACKEND_PORT=//p' "$INFRA_DIR/.env" | tail -n 1)"; port="${port:-8080}"
    printf 'readiness: '; curl -fsS "http://127.0.0.1:$port/api/v1/health/readiness" || echo "KHONG TRA LOI"; echo ;;
  logs)    dc logs --tail 200 -f "$@" ;;
  backup)  bash "$INFRA_DIR/scripts/backup.sh" --out-dir "$INFRA_DIR/../backups" --project-name "$PROJECT" --infra-dir "$INFRA_DIR" "${BACKUP_OVERLAYS[@]}" --include-neo4j-dump ;;
  update)  dc up -d --build backend frontend ;;
  compose) dc "$@" ;;
  -h|--help|help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *) echo "Lenh khong hop le: $cmd (start|stop|restart|status|logs|backup|update|compose)" >&2; exit 2 ;;
esac
