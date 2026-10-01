#!/bin/bash
# Nạp dữ liệu lớn vào lớp thử của drill. Mật khẩu MySQL lấy từ biến môi trường CỦA CONTAINER (không đọc .env).
#   loadtest/sql/seed-bulk.sh                # dùng loadtest/.state/state.json, container classroom-drill-mysql
# Chỉ chạy với container của drill (tên chứa "drill"): từ chối container khác.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CONTAINER="${LOADTEST_MYSQL_CONTAINER:-classroom-drill-mysql}"
case "$CONTAINER" in *drill*) ;; *) echo "Từ chối: $CONTAINER không phải container drill" >&2; exit 2;; esac
STATE="${LOADTEST_STATE_FILE:-$HERE/../.state/state.json}"
CLS="$(node -e "console.log(JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')).classId)" "$STATE")"
{ echo "SET @cls = '$CLS';"; cat "$HERE/seed-bulk.sql"; } | MSYS_NO_PATHCONV=1 docker exec -i "$CONTAINER" sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot $MYSQL_DATABASE -B'
