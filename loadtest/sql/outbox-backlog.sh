#!/bin/bash
# R20-05 - nạp một tồn đọng PROCESSED giả (mặc định 300 000 hàng) vào outbox_events của DRILL rồi đo chi phí MySQL của một lần thăm dò rảnh.
#   loadtest/sql/outbox-backlog.sh [rows=300000] [--bench-only]
# Chỉ chạm container classroom-drill-mysql (từ chối tên không chứa "drill"). Hàng giả có aggregate_type='EXAM', aggregate_id='r21-synthetic-*'
# và processed_at/created_at rải trong 20 ngày qua (BACKLOG_SPREAD_DAYS), nên job retention (mặc định 7 ngày) sẽ dọn phần cũ hơn 7 ngày - cách kiểm chứng retention trên drill;
# đặt BACKLOG_SPREAD_DAYS=6 để giữ nguyên bảng lớn khi đo các kịch bản khác.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROWS="${1:-300000}"
SPREAD="${BACKLOG_SPREAD_DAYS:-20}"   # tuổi hàng giả rải đều trong [0, SPREAD) ngày; > 7 ngày thì job retention sẽ dọn (đặt 6 để giữ nguyên bảng lớn)
PREFIX="${DRILL_PREFIX:-classroom-drill}"
case "$PREFIX" in *drill*) ;; *) echo "Từ chối: DRILL_PREFIX='$PREFIX' không phải stack drill" >&2; exit 2;; esac
docker inspect "$PREFIX-mysql" >/dev/null 2>&1 || { echo "Không thấy $PREFIX-mysql - dựng stack drill trước" >&2; exit 2; }
run() { MSYS_NO_PATHCONV=1 docker exec -i "$PREFIX-mysql" sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot $MYSQL_DATABASE "$@"' sh "$@"; }
if [ "${2:-}" != "--bench-only" ] && [ "$ROWS" != "0" ]; then
  echo "nạp $ROWS hàng PROCESSED giả vào $PREFIX-mysql ..."
  run -e "SET SESSION cte_max_recursion_depth = $((ROWS + 10));
INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload_json, status, retry_count, created_at, processed_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < $ROWS)
SELECT UUID(), 'EXAM', CONCAT('r21-synthetic-', n MOD 50000), 'EXAM_SUBMITTED', '{\"userId\":\"x\",\"classId\":\"y\"}', 'PROCESSED', 0,
       NOW() - INTERVAL (n MOD $SPREAD) DAY, NOW() - INTERVAL (n MOD $SPREAD) DAY FROM seq;" 2>&1 | grep -v Warning
fi
run -N -e "select concat('outbox_events rows=', count(*), ' processed=', coalesce(sum(status='PROCESSED'),0), ' pending=', coalesce(sum(status='PENDING'),0), ' dead=', coalesce(sum(status='DEAD_LETTER'),0)) from outbox_events" 2>&1 | grep -v Warning
echo "--- chi phí MySQL của một lần thăm dò rảnh (avg_ms) ---"
run < "$HERE/outbox-poll-bench.sql" 2>&1 | grep -v Warning
