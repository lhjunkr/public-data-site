#!/usr/bin/env bash
# 출처별 매일 갱신 로컬 확인 (12/16): 테스트 → 예약 시각을 지금부터 2 ~ 5분 뒤로 바꿔 앱 실행
#   → K-Startup · 마이홈 · 잡알리오 · 지방공공기관이 각자 시각에 스스로 도는지 → collect_run 출처별 결과 → 앱 종료
#
# 사용: 리포 폴더에서  bash scripts/check-schedule-local.sh   (약 8분)
#   먼저 Docker(MySQL)가 떠 있어야 하고, 8080 에 다른 앱이 없어야 한다(앱 창의 bootRun 은 Ctrl+C 로 끈다).
#   .env 에 DATA_GO_KR_KEY 가 있어야 한다. 온통청년은 이미 매일 돌고 있고 오래 걸려(1.5분) 이번 확인에서는 뺀다.
#   운영 시각(04:10 ~ 04:35)은 바꾸지 않는다. 이 실행에만 명령줄 인자로 덮어쓴다.
#   결과는 화면과 build/check-schedule.txt 에 남는다(build/ 는 리포에 올라가지 않음).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/check-schedule.txt
BASE=http://127.0.0.1:8080
mkdir -p build
: > "$OUT"
log() { echo "$*" | tee -a "$OUT"; }
sql() {
  docker compose exec -T mysql sh -c 'exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" 2>/dev/null'
}
cron_in() { date -v+"$1"M '+0 %M %H * * *'; }

if curl -s -o /dev/null -m 2 "$BASE/health"; then
  log "8080 에서 앱이 이미 돌고 있습니다. 앱 창에서 Ctrl+C 로 끈 뒤 다시 실행하십시오."
  exit 1
fi

log "== 1. 테스트 ($(date '+%H:%M:%S'))"
./gradlew test > build/check-test.log 2>&1
rc=$?
cat build/test-results/test/*.xml | grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  | awk -F'"' '{t+=$2; f+=$6; e+=$8} END {print "tests="t, "failures="f, "errors="e}' | tee -a "$OUT"
log "(gradle exit $rc)"
if [ "$rc" -ne 0 ]; then
  grep -E 'FAILED|Error|Exception' build/check-test.log | head -30 | tee -a "$OUT"
  exit 1
fi

log "== 2. 앱 실행 — 예약 시각을 2 ~ 5분 뒤로 덮어쓴다"
./gradlew bootJar -q > build/check-bootjar.log 2>&1 || { log "bootJar 실패 → build/check-bootjar.log"; exit 1; }
JAR=$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)
STARTED=$(date '+%Y-%m-%d %H:%M:%S')
KS=$(cron_in 2); MH=$(cron_in 3); AL=$(cron_in 4); CE=$(cron_in 5)
java -jar "$JAR" "--batch.crons.youth=-" "--batch.crons.kstartup=$KS" "--batch.crons.myhome=$MH" \
  "--batch.crons.alio=$AL" "--batch.crons.cleaneye=$CE" > build/check-app.log 2>&1 &
APP=$!
trap 'kill "$APP" 2>/dev/null; wait "$APP" 2>/dev/null' EXIT
for _ in $(seq 1 60); do
  curl -s -o /dev/null -m 2 "$BASE/health" && break
  sleep 1
done
curl -s -m 5 "$BASE/health" | tee -a "$OUT"; echo | tee -a "$OUT"
grep -E '매일 갱신 예약' build/check-app.log | sed -E 's/^.*SchedulingConfig *: //' | tee -a "$OUT"

log "== 3. 예약 시각에 스스로 도는지 기다림 (최대 8분, 시작 $(date '+%H:%M:%S'))"
DONE_RE='매일 갱신 (완료|실패|건너뜀)|대량 비활성 감지'
for _ in $(seq 1 96); do
  n=$(grep -cE "$DONE_RE" build/check-app.log)
  [ "$n" -ge 4 ] && break
  sleep 5
done
grep -E "$DONE_RE" build/check-app.log | sed -E 's/^([0-9T:.-]+).*DailyRefreshJob *: /\1 /' | cut -c1-220 | tee -a "$OUT"

log "== 4. collect_run — 이번 실행에서 출처별 결과"
sql <<SQL | tee -a "$OUT"
SELECT source, status, tot_count, items_seen, DATE_FORMAT(started_at, '%H:%i:%s') AS started,
       TIMESTAMPDIFF(SECOND, started_at, finished_at) AS sec
  FROM collect_run WHERE started_at >= '$STARTED' ORDER BY started_at;
SELECT source, COUNT(*) AS active, SUM(apply_end_date < CURDATE()) AS past_deadline_still_active
  FROM policy WHERE active = TRUE GROUP BY source ORDER BY source;
SQL

log "== 끝 ($(date '+%H:%M:%S')). 결과: $OUT"
