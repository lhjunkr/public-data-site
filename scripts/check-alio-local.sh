#!/usr/bin/env bash
# 잡알리오 로컬 확인 한 번에 (12/13): 테스트 → 앱 실행 → 잡알리오 갱신 2회(수집 + 정규화) → DB 집계 · 이탈 예측 → 상세 · 검색 → 앱 종료
#
# 사용: 리포 폴더에서  bash scripts/check-alio-local.sh
#   먼저 Docker(MySQL)가 떠 있어야 하고, 8080 에 다른 앱이 없어야 한다(앱 창의 bootRun 은 Ctrl+C 로 끈다).
#   .env 에 DATA_GO_KR_KEY(공공데이터포털 Decoding 키)가 있어야 한다(K-Startup · 마이홈과 같은 키).
#   결과는 화면과 build/check-alio.txt 에 남는다(build/ 는 리포에 올라가지 않음).
#   관리 토큰 · 인증키는 .env 에서 바로 넘기고 화면 · 파일에 쓰지 않는다.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/check-alio.txt
BASE=http://127.0.0.1:8080
mkdir -p build
: > "$OUT"
log() { echo "$*" | tee -a "$OUT"; }
admin() {
  grep '^ADMIN_TOKEN=' .env | sed 's/^ADMIN_TOKEN=/X-Admin-Token: /' \
    | curl -sS -m 180 -X POST "$BASE$1" -H @- | tee -a "$OUT"
  echo | tee -a "$OUT"
}
sql() {
  docker compose exec -T mysql sh -c 'exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" 2>/dev/null'
}

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

log "== 2. 앱 실행"
./gradlew bootJar -q > build/check-bootjar.log 2>&1 || { log "bootJar 실패 → build/check-bootjar.log"; exit 1; }
JAR=$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)
java -jar "$JAR" > build/check-app.log 2>&1 &
APP=$!
trap 'kill "$APP" 2>/dev/null; wait "$APP" 2>/dev/null' EXIT
for _ in $(seq 1 60); do
  curl -s -o /dev/null -m 2 "$BASE/health" && break
  sleep 1
done
curl -s -m 5 "$BASE/health" | tee -a "$OUT"; echo | tee -a "$OUT"

log "== 3. 잡알리오 갱신 1회차 (수집 → 정규화) ($(date '+%H:%M:%S'))"
admin "/api/v1/admin/refresh?source=alio"
log "== 4. 잡알리오 갱신 2회차 (멱등 확인: 행 수 · 연결 수가 같아야 함) ($(date '+%H:%M:%S'))"
admin "/api/v1/admin/refresh?source=alio"

log "== 5. DB 집계"
sql <<'SQL' | tee -a "$OUT"
SELECT source, status, tot_count, items_seen, started_at, TIMESTAMPDIFF(SECOND, started_at, finished_at) AS sec
  FROM collect_run WHERE source = 'alio' ORDER BY id DESC LIMIT 2;
SELECT COUNT(*) AS raw_rows FROM policy_raw WHERE source = 'alio';
SELECT COUNT(*) AS active, SUM(apply_end_date IS NULL) AS open_ended,
       SUM(extra_qualification IS NOT NULL) AS needs_check, SUM(ref_url IS NULL) AS no_org_url
  FROM policy WHERE source = 'alio' AND active = TRUE;
SELECT pc.category_code, COUNT(*) AS n FROM policy_category pc JOIN policy p ON p.id = pc.policy_id
 WHERE p.source = 'alio' AND p.active = TRUE GROUP BY pc.category_code ORDER BY n DESC;
SELECT COUNT(*) AS sido_links, COUNT(DISTINCT ps.policy_id) AS with_sido FROM policy_sido ps JOIN policy p ON p.id = ps.policy_id
 WHERE p.source = 'alio' AND p.active = TRUE;
SELECT SUM(NOT EXISTS (SELECT 1 FROM policy_sido ps WHERE ps.policy_id = p.id)) AS no_sido,
       SUM(NOT EXISTS (SELECT 1 FROM policy_category pc WHERE pc.policy_id = p.id)) AS no_chip
  FROM policy p WHERE p.source = 'alio' AND p.active = TRUE;
SELECT ps.sido, COUNT(*) AS n FROM policy_sido ps JOIN policy p ON p.id = ps.policy_id
 WHERE p.source = 'alio' AND p.active = TRUE GROUP BY ps.sido ORDER BY n DESC;
SELECT COUNT(*) AS same_title_groups, SUM(c) AS postings FROM (
  SELECT COUNT(*) AS c FROM policy WHERE source = 'alio' AND active = TRUE GROUP BY supervising_org, title HAVING c > 1) t;
SELECT source, COUNT(*) AS active FROM policy WHERE active = TRUE GROUP BY source;
SQL

log "== 6. 이탈 예측 (대량 비활성 상한과 비교) · 11/25 표본과 비교"
sql <<'SQL' > build/check-alio-raw.tsv
SELECT policy_no, JSON_UNQUOTE(raw_json->'$.pbancEndYmd') FROM policy_raw
 WHERE source = 'alio' AND fetched_at = (SELECT MAX(started_at) FROM collect_run WHERE source = 'alio' AND status = 'SUCCESS');
SQL
python3 - <<'PY' | tee -a "$OUT"
import collections, datetime, glob, json
rows = [l.rstrip("\n").split("\t") for l in open("build/check-alio-raw.tsv", encoding="utf-8")][1:]
today = datetime.date.today()
now = {r[0] for r in rows}
ends = collections.Counter(r[1] for r in rows)
active = len(now)
limit = max(50, active * 10 // 100)
print(f"오늘 진행 중 {active}건 · 대량 비활성 상한 {limit}건 (max(50, 10%))")
print("앞으로 14일 날짜별 마감 수 (다음 날 응답에서 빠질 예정):")
worst = 0
for i in range(14):
    d = today + datetime.timedelta(days=i)
    n = ends.get(d.strftime("%Y%m%d"), 0)
    worst = max(worst, n)
    print(f"  {d} ({'월화수목금토일'[d.weekday()]}) {n}건" + ("  ← 상한 초과" if n > limit else ""))
print(f"하루 최대 {worst}건 / 상한 {limit}건")
old = set()
for f in sorted(glob.glob("samples/alio/list-[0-9].json")):
    old |= {str(i["recrutPblntSn"]) for i in json.load(open(f, encoding="utf-8"))["result"]}
if old:
    print(f"11/25 표본 {len(old)}건 → 오늘 {active}건: 사라짐 {len(old - now)} · 새로 {len(now - old)} · 그대로 {len(old & now)}")
PY

log "== 7. 상세 (잡알리오 1건의 지원 내용 · 링크)"
sql <<'SQL' > build/check-alio-one.tsv
SELECT id, policy_no, title, apply_url, ref_url FROM policy
 WHERE source = 'alio' AND active = TRUE AND apply_end_date >= CURDATE() + INTERVAL 3 DAY ORDER BY id LIMIT 1;
SQL
ID=$(sed -n 2p build/check-alio-one.tsv | cut -f1)
sed -n 2p build/check-alio-one.tsv | awk -F'\t' '{print "공고 번호 " $2 " | " $3; print "신청하기(잡알리오 원문): " $4; print "참고(기관 채용 페이지): " $5}' | tee -a "$OUT"
curl -sS -m 10 "$BASE/api/v1/policies/$ID" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("supportContent"))' | tee -a "$OUT"

log "== 8. 검색 (25세 · 서울) — 공공기관 채용 탭이 닫혀 있어 잡알리오는 0건이어야 함"
curl -sS -m 30 -X POST "$BASE/api/v1/policies/match" -H 'Content-Type: application/json' \
  -d '{"age":25,"sido":"11"}' > build/check-alio-match.json
python3 -c 'import collections,json; d=json.load(open("build/check-alio-match.json")); print("전체", d["count"], dict(collections.Counter(p.get("source") for p in d["policies"])))' | tee -a "$OUT"

log "== 끝 ($(date '+%H:%M:%S')). 위 '신청하기' 주소를 브라우저로 열어 같은 공고인지 확인하십시오. 앱을 끕니다."
