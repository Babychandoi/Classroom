#!/bin/bash
# Chạy một script của bộ loadtest BÊN TRONG mạng Docker của stack drill (node:20-alpine, có NET_ADMIN để tạo nhiều IP nguồn).
#   loadtest/run-in-drill.sh seed.js --users 200
#   loadtest/run-in-drill.sh scenarios/exam-burst.js --users 200 --duration 20
#   loadtest/run-in-drill.sh smoke.js
# Từ chối mạng không phải của drill (tên phải chứa "drill"). Dữ liệu trạng thái nằm ở loadtest/.state/ (đã .gitignore).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
NETWORK="${LOADTEST_NETWORK:-classroom-drill_classroom-net}"
case "$NETWORK" in *drill*) ;; *) echo "Từ chối: mạng '$NETWORK' không phải mạng của stack drill (LOADTEST_ALLOW_ANY_NETWORK=1 để bỏ qua)" >&2
  [ "${LOADTEST_ALLOW_ANY_NETWORK:-}" = "1" ] || exit 2;; esac
ENVS=(-e "BASE_URL=${BASE_URL:-http://frontend}" -e NODE_OPTIONS=--max-old-space-size=2048)
for v in USERS LOADTEST_STATE LOADTEST_ALLOW_REMOTE LOADTEST_ALLOW_MAIN_STACK; do
  [ -n "${!v:-}" ] && ENVS+=(-e "$v=${!v}")
done
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" --cap-add NET_ADMIN "${ENVS[@]}" \
  -v "$HERE:/work" -w /work node:20-alpine node "$@"
