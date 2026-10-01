#!/bin/bash
# R20-04 - gián đoạn kho chiếu MongoDB / Neo4j TRÊN STACK DRILL (không bao giờ stack thật).
#   loadtest/scenarios/outbox-outage.sh <mongodb|neo4j> [thời-gian-gián-đoạn-giây=120] [joins=30] [submits=10]
# Điều kiện: stack drill đang chạy (RUNBOOK 2.4) và đã seed: `loadtest/run-in-drill.sh seed.js --users 60 --questions 3 --exams 1`.
# Quy trình: dừng container drill của kho -> sinh sự kiện (joins vào MỘT lớp + nộp bài) -> hai đợt "thăm dò" các tác vụ nền cốt lõi: (A) ngay sau khi sinh
# sự kiện và (B) giữa thời gian gián đoạn, mỗi đợt ép MỘT lượt thi hết hạn (tác vụ chốt lượt thi quá hạn phải chốt) và cài MỘT việc tính lại xếp hạng
# (sweeper phải xử lý) -> chờ hết thời gian -> khởi động lại kho -> đo thời gian dồn hàng đợi.
# Kết quả cuối in các dòng "METRIC ten=giatri" để so trước/sau. Chỉ dùng container tên classroom-drill-* (DRILL_PREFIX).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SVC="${1:-}"; OUT="${2:-120}"; JOINS="${3:-30}"; SUBMITS="${4:-10}"
PREFIX="${DRILL_PREFIX:-classroom-drill}"
case "$PREFIX" in *drill*) ;; *) echo "Từ chối: DRILL_PREFIX='$PREFIX' không phải stack drill" >&2; exit 2;; esac
case "$SVC" in mongodb|neo4j) ;; *) echo "Cách dùng: $0 <mongodb|neo4j> [giây] [joins] [submits]" >&2; exit 2;; esac
for c in mysql mongodb neo4j backend; do
  docker inspect "$PREFIX-$c" >/dev/null 2>&1 || { echo "Không thấy container $PREFIX-$c - dựng stack drill trước (RUNBOOK 2.4)" >&2; exit 2; }
done
q() { MSYS_NO_PATHCONV=1 docker exec -i "$PREFIX-mysql" sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot $MYSQL_DATABASE -B -N "$@"' sh -e "$1" 2>/dev/null; }
ts() { date -u +%H:%M:%S; }
now() { date +%s; }
CATCHUP_MAX="${CATCHUP_MAX:-300}"

declare -A ATT USR ARMED DONE_ATT DONE_JOB

MAXSEQ=$(q "select coalesce(max(sequence_no),0) from outbox_events")
echo "$(ts) baseline max(sequence_no)=$MAXSEQ backend image: $(docker inspect -f '{{.Image}}' "$PREFIX-backend" | cut -c8-19)"
docker stop "$PREFIX-$SVC" >/dev/null && echo "$(ts) STOPPED $PREFIX-$SVC"
T0=$(now)

PRODUCED=$("$HERE/../run-in-drill.sh" scenarios/outbox-produce.js --joins "$JOINS" --submits "$SUBMITS" --probes 2 --tag "$SVC" | tee /dev/stderr | sed -n 's/^RESULT //p')
CLS=$(echo "$PRODUCED" | sed -n 's/.*"classId":"\([^"]*\)".*/\1/p')
ATT[A]=$(echo "$PRODUCED" | sed -n 's/.*"probes":\[{"attemptId":"\([^"]*\)".*/\1/p')
USR[A]=$(echo "$PRODUCED" | sed -n 's/.*"probes":\[{"attemptId":"[^"]*","userId":"\([^"]*\)".*/\1/p')
ATT[B]=$(echo "$PRODUCED" | sed -n 's/.*},{"attemptId":"\([^"]*\)".*/\1/p')
USR[B]=$(echo "$PRODUCED" | sed -n 's/.*},{"attemptId":"[^"]*","userId":"\([^"]*\)".*/\1/p')
[ -n "$CLS" ] && [ -n "${ATT[A]}" ] && [ -n "${ATT[B]}" ] || { echo "producer không trả kết quả đủ; khởi động lại $SVC"; docker start "$PREFIX-$SVC" >/dev/null; exit 1; }

arm() { # $1 = A|B : ép lượt thi hết hạn + cài việc tính lại xếp hạng
  q "update exam_attempts set ends_at = now(6) - interval 1 minute where id='${ATT[$1]}'; insert into leaderboard_recalc_jobs (id,class_id,user_id) values (uuid(),'$CLS','${USR[$1]}');"
  ARMED[$1]=$(now)
  echo "$(ts) probe $1 armed: expired attempt ${ATT[$1]}, recalc job for ${USR[$1]}"
}
JOBQ() { q "select count(*) from leaderboard_recalc_jobs where class_id='$CLS' and user_id='${USR[$1]}'"; }
check_probes() {
  for k in A B; do
    [ -n "${ARMED[$k]:-}" ] || continue
    if [ -z "${DONE_ATT[$k]:-}" ]; then [ "$(q "select status from exam_attempts where id='${ATT[$k]}'")" != "IN_PROGRESS" ] && DONE_ATT[$k]=$(( $(now) - ARMED[$k] )); fi
    if [ -z "${DONE_JOB[$k]:-}" ]; then [ "$(JOBQ $k)" = "0" ] && DONE_JOB[$k]=$(( $(now) - ARMED[$k] )); fi
  done
}
probe_text() { for k in A B; do [ -n "${ARMED[$k]:-}" ] && printf 'probe%s(attempt=%ss job=%ss) ' "$k" "${DONE_ATT[$k]:--}" "${DONE_JOB[$k]:--}"; done; }

arm A
DEAD_MAX=0; B_ARMED=0; LAST_PRINT=0
while [ $(( $(now) - T0 )) -lt "$OUT" ]; do
  if [ "$B_ARMED" = 0 ] && [ $(( $(now) - T0 )) -ge $(( OUT / 2 )) ]; then arm B; B_ARMED=1; fi
  check_probes
  D=$(q "select coalesce(sum(status='DEAD_LETTER'),0) from outbox_events where sequence_no>$MAXSEQ"); [ "${D:-0}" -gt "$DEAD_MAX" ] && DEAD_MAX=$D
  if [ $(( $(now) - LAST_PRINT )) -ge 10 ]; then
    LAST_PRINT=$(now)
    echo "$(ts) +$(( $(now) - T0 ))s outbox(new): $(q "select group_concat(concat(status,'/r',retry_count,'=',c) order by status) from (select status,retry_count,count(*) c from outbox_events where sequence_no>$MAXSEQ group by status,retry_count) x") | $(probe_text)| readiness=$(curl -s -o /dev/null -w '%{http_code}/%{time_total}s' http://127.0.0.1:18080/api/v1/health/readiness)"
  fi
  sleep 2
done
[ "$B_ARMED" = 0 ] && { arm B; B_ARMED=1; }
docker start "$PREFIX-$SVC" >/dev/null && echo "$(ts) STARTED $PREFIX-$SVC (outage $(( $(now) - T0 ))s)"
T1=$(now)
DONE=""
while [ $(( $(now) - T1 )) -lt "$CATCHUP_MAX" ]; do
  check_probes
  R=$(q "select coalesce(sum(status<>'PROCESSED'),0), coalesce(sum(status='DEAD_LETTER'),0), count(*) from outbox_events where sequence_no>$MAXSEQ")
  set -- $R
  [ "$2" -gt "$DEAD_MAX" ] && DEAD_MAX=$2
  echo "$(ts) +$(( $(now) - T1 ))s after restart: notProcessed/dead/total=$1/$2/$3 | $(probe_text)"
  if [ "$1" = "0" ]; then DONE=$(( $(now) - T1 )); break; fi
  sleep 3
done
check_probes
echo "ordering check (per aggregate: processed_at non-decreasing in sequence order; must be 0 inversions):"
q "select aggregate_type, aggregate_id, count(*) n, sum(bad) inversions from (select aggregate_type, aggregate_id, case when processed_at < lag(processed_at) over (partition by aggregate_type,aggregate_id order by sequence_no) then 1 else 0 end bad from outbox_events where sequence_no>$MAXSEQ) x group by aggregate_type, aggregate_id order by n desc limit 3"
echo "final states:"; q "select status, retry_count, coalesce(failure_kind,'-'), count(*) from outbox_events where sequence_no>$MAXSEQ group by status, retry_count, failure_kind"
echo "METRIC store=$SVC outage_s=$OUT joins=$JOINS submits=$SUBMITS"
for k in A B; do
  echo "METRIC probe${k}_attempt_finalized_after_s=${DONE_ATT[$k]:-NOT_DONE}"
  echo "METRIC probe${k}_recalc_job_consumed_after_s=${DONE_JOB[$k]:-NOT_DONE}"
done
echo "METRIC dead_letters_max=$DEAD_MAX"
echo "METRIC backlog_drained_after_restart_s=${DONE:-NOT_DRAINED_IN_${CATCHUP_MAX}s}"
