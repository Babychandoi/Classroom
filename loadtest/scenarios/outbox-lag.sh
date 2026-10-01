#!/bin/bash
# R20-05 - độ trễ chiếu (projection lag) khi NHIỀU thành viên vào MỘT lớp: sự kiện MEMBER_JOINED cùng aggregate. TRÊN STACK DRILL.
#   loadtest/scenarios/outbox-lag.sh [joins=200]
# Điều kiện: `loadtest/run-in-drill.sh seed.js --users 200 --questions 3 --exams 1` đã chạy. In "METRIC lag_after_last_join_s=..." (mục tiêu < 30 s;
# trước khi sửa: mỗi 2 giây chỉ chiếu được MỘT sự kiện của aggregate => ~2 giây x số thành viên).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JOINS="${1:-200}"
PREFIX="${DRILL_PREFIX:-classroom-drill}"
case "$PREFIX" in *drill*) ;; *) echo "Từ chối: DRILL_PREFIX='$PREFIX' không phải stack drill" >&2; exit 2;; esac
docker inspect "$PREFIX-mysql" >/dev/null 2>&1 || { echo "Không thấy $PREFIX-mysql - dựng stack drill trước" >&2; exit 2; }
q() { MSYS_NO_PATHCONV=1 docker exec -i "$PREFIX-mysql" sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot $MYSQL_DATABASE -B -N "$@"' sh -e "$1" 2>/dev/null; }
ms() { date +%s%3N; }
LIMIT="${LAG_TIMEOUT:-900}"

MAXSEQ=$(q "select coalesce(max(sequence_no),0) from outbox_events")
PRODUCED=$("$HERE/../run-in-drill.sh" scenarios/outbox-produce.js --joins "$JOINS" --submits 0 --tag lag | tee /dev/stderr | sed -n 's/^RESULT //p')
T_END=$(ms)
CLS=$(echo "$PRODUCED" | sed -n 's/.*"classId":"\([^"]*\)".*/\1/p')
[ -n "$CLS" ] || { echo "producer không trả kết quả" >&2; exit 1; }
TOTAL=$(q "select count(*) from outbox_events where sequence_no>$MAXSEQ and aggregate_id='$CLS'")
echo "class $CLS: $TOTAL outbox events after $JOINS joins"
FIRST=""
while true; do
  R=$(q "select coalesce(sum(status='PROCESSED'),0), coalesce(sum(status='DEAD_LETTER'),0) from outbox_events where sequence_no>$MAXSEQ and aggregate_id='$CLS'")
  set -- $R
  ELAPSED=$(( ($(ms) - T_END) ))
  [ -z "$FIRST" ] && echo "$(date -u +%H:%M:%S) +${ELAPSED}ms processed=$1/$TOTAL dead=$2"
  FIRST=1
  if [ "$1" -ge "$TOTAL" ]; then break; fi
  if [ "$ELAPSED" -gt $(( LIMIT * 1000 )) ]; then echo "METRIC lag_after_last_join_s=NOT_DONE_IN_${LIMIT}s processed=$1/$TOTAL"; exit 1; fi
  sleep 1
done
echo "$(date -u +%H:%M:%S) +${ELAPSED}ms processed=$TOTAL/$TOTAL"
echo "METRIC joins=$JOINS events=$TOTAL"
echo "METRIC lag_after_last_join_s=$(( ELAPSED / 1000 ))"
echo "METRIC ordering_inversions=$(q "select coalesce(sum(bad),0) from (select case when processed_at < lag(processed_at) over (order by sequence_no) then 1 else 0 end bad from outbox_events where sequence_no>$MAXSEQ and aggregate_id='$CLS') x")"
