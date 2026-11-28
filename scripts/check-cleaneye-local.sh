#!/usr/bin/env bash
# 지방공공기관 로컬 확인 한 번에 (12/14): 테스트 → 앱 실행 → 지방공공기관 갱신 2회 → DB 집계 · 잡알리오 중복 측정 · 이탈 예측
#   → 상세 · 검색 → 앱 종료
#
# 사용: 리포 폴더에서  bash scripts/check-cleaneye-local.sh
#   먼저 Docker(MySQL)가 떠 있어야 하고, 8080 에 다른 앱이 없어야 한다(앱 창의 bootRun 은 Ctrl+C 로 끈다).
#   .env 에 DATA_GO_KR_KEY(공공데이터포털 Decoding 키)가 있어야 한다.
#   잡알리오 중복 측정은 로컬 DB 의 잡알리오 공고와 비교한다(11/28 check-alio-local.sh 로 넣은 것).
#   결과는 화면과 build/check-cleaneye.txt 에 남는다(build/ 는 리포에 올라가지 않음).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/check-cleaneye.txt
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

log "== 3. 지방공공기관 갱신 1회차 (수집 → 정규화) ($(date '+%H:%M:%S'))"
admin "/api/v1/admin/refresh?source=cleaneye"
log "== 4. 지방공공기관 갱신 2회차 (멱등 확인) ($(date '+%H:%M:%S'))"
admin "/api/v1/admin/refresh?source=cleaneye"
grep -E '지방공공기관 (수집 완료|정규화 완료)|잡알리오와 기관 이름' build/check-app.log | sed -E 's/^.*(Normalizer|Collector) +: //' | tee -a "$OUT"

log "== 5. DB 집계"
sql <<'SQL' | tee -a "$OUT"
SELECT source, status, tot_count, items_seen, started_at, TIMESTAMPDIFF(SECOND, started_at, finished_at) AS sec
  FROM collect_run WHERE source = 'cleaneye' ORDER BY id DESC LIMIT 2;
SELECT COUNT(*) AS raw_rows,
       SUM(JSON_UNQUOTE(raw_json->'$.JOB_TYPE') = '비상임') AS non_standing
  FROM policy_raw WHERE source = 'cleaneye';
SELECT COUNT(*) AS active, SUM(apply_end_date IS NULL) AS open_ended,
       SUM(extra_qualification IS NOT NULL) AS needs_check, SUM(apply_url IS NULL) AS no_url,
       SUM(apply_start_date > CURDATE()) AS not_started
  FROM policy WHERE source = 'cleaneye' AND active = TRUE;
SELECT category_medium AS job_type, COUNT(*) AS n FROM policy WHERE source = 'cleaneye' AND active = TRUE
 GROUP BY category_medium ORDER BY n DESC;
SELECT pc.category_code, COUNT(*) AS n FROM policy_category pc JOIN policy p ON p.id = pc.policy_id
 WHERE p.source = 'cleaneye' AND p.active = TRUE GROUP BY pc.category_code ORDER BY n DESC;
SELECT SUM(NOT EXISTS (SELECT 1 FROM policy_sido ps WHERE ps.policy_id = p.id)) AS no_sido,
       SUM(NOT EXISTS (SELECT 1 FROM policy_category pc WHERE pc.policy_id = p.id)) AS no_chip
  FROM policy p WHERE p.source = 'cleaneye' AND p.active = TRUE;
SELECT ps.sido, COUNT(*) AS n FROM policy_sido ps JOIN policy p ON p.id = ps.policy_id
 WHERE p.source = 'cleaneye' AND p.active = TRUE GROUP BY ps.sido ORDER BY n DESC;
SELECT COUNT(*) AS multi_row_postings, SUM(c) AS rows_in_them, MAX(c) AS max_rows FROM (
  SELECT COUNT(*) AS c FROM policy_raw WHERE source = 'cleaneye'
     AND fetched_at = (SELECT MAX(started_at) FROM collect_run WHERE source = 'cleaneye' AND status = 'SUCCESS')
   GROUP BY JSON_UNQUOTE(raw_json->'$.ENT_NAME'), JSON_UNQUOTE(raw_json->'$.ENT_TITLE') HAVING c > 1) t;
SELECT COUNT(*) AS same_card_title FROM (
  SELECT COUNT(*) AS c FROM policy WHERE source = 'cleaneye' AND active = TRUE
   GROUP BY supervising_org, title HAVING c > 1) t;
SELECT source, COUNT(*) AS active FROM policy WHERE active = TRUE GROUP BY source;
SQL

log "== 6. 잡알리오와 중복 측정 (기관 이름 · 제목, 공백 · 법인 표기 · 문장부호 무시)"
sql <<'SQL' > build/check-cleaneye-orgs.tsv
SELECT source, supervising_org, title FROM policy
 WHERE source IN ('alio', 'cleaneye') AND active = TRUE AND (apply_end_date IS NULL OR apply_end_date >= CURDATE());
SQL
python3 - <<'PY' | tee -a "$OUT"
import collections, re
rows = [l.rstrip("\n").split("\t") for l in open("build/check-cleaneye-orgs.tsv", encoding="utf-8")][1:]
org = lambda s: re.sub(r"[\s()\[\]·.,]", "", re.sub(r"\(재\)|\(주\)|\(사\)|㈜|재단법인|사단법인|주식회사", "", s or ""))
ttl = lambda s: re.sub(r"[\s()\[\]·.,_\-]", "", s or "")
by = collections.defaultdict(list)
for src, o, t in rows:
    by[src].append((o, t))
a, c = by["alio"], by["cleaneye"]
ao, co = {org(o) for o, _ in a}, {org(o) for o, _ in c}
print(f"잡알리오 마감 전 {len(a)}건 · 기관 {len(ao)}곳 / 지방공공기관 마감 전 {len(c)}행 · 기관 {len(co)}곳")
print(f"기관 이름 같음: {len(ao & co)}곳 {sorted(ao & co)[:10]}")
part = sorted({(x, y) for x in ao for y in co if len(x) >= 4 and len(y) >= 4 and x != y and (x in y or y in x)})
print(f"기관 이름 한쪽이 다른 쪽을 포함(4글자 이상): {len(part)}쌍 {part[:10]}")
at, ct = {ttl(t) for _, t in a}, {ttl(t) for _, t in c}
print(f"제목 같음: {len(at & ct)}건 {sorted(at & ct)[:5]}")
PY

log "== 7. 이탈 예측 (대량 비활성 상한과 비교) · 11/25 표본과 비교"
sql <<'SQL' > build/check-cleaneye-raw.tsv
SELECT policy_no, JSON_UNQUOTE(raw_json->'$.PUB_END_DATE'), JSON_UNQUOTE(raw_json->'$.JOB_TYPE') FROM policy_raw
 WHERE source = 'cleaneye' AND fetched_at = (SELECT MAX(started_at) FROM collect_run WHERE source = 'cleaneye' AND status = 'SUCCESS');
SQL
python3 - <<'PY' | tee -a "$OUT"
import collections, datetime, glob
import xml.etree.ElementTree as ET
rows = [l.rstrip("\n").split("\t") for l in open("build/check-cleaneye-raw.tsv", encoding="utf-8")][1:]
rows = [r for r in rows if r[2] != "비상임"]
today = datetime.date.today()
now = {r[0] for r in rows}
ends = collections.Counter(r[1] for r in rows)
active = len(now)
limit = max(50, active * 10 // 100)
print(f"오늘 싣는 행 {active}건 · 대량 비활성 상한 {limit}건 (max(50, 10%))")
worst = 0
for i in range(14):
    d = today + datetime.timedelta(days=i)
    n = ends.get(d.isoformat(), 0)
    worst = max(worst, n)
    print(f"  {d} ({'월화수목금토일'[d.weekday()]}) {n}건" + ("  ← 상한 초과" if n > limit else ""))
print(f"하루 최대 {worst}건 / 상한 {limit}건")
old = set()
for f in sorted(glob.glob("samples/local/sido-007*.xml")):
    for it in ET.parse(f).getroot().iter("item"):
        if (it.findtext("JOB_TYPE") or "") != "비상임":
            old.add((it.findtext("NO") or "").strip())
if old:
    print(f"11/25 표본 {len(old)}행 → 오늘 {active}행: 사라짐 {len(old - now)} · 새로 {len(now - old)} · 그대로 {len(old & now)}")
PY

log "== 8. 상세 (같은 공고 여러 행 중 1건 · 지원 내용 · 링크)"
sql <<'SQL' > build/check-cleaneye-one.tsv
SELECT p.id, p.policy_no, p.title, p.apply_url, p.extra_qualification FROM policy p
 WHERE p.source = 'cleaneye' AND p.active = TRUE AND p.apply_end_date >= CURDATE() + INTERVAL 3 DAY
 ORDER BY (SELECT COUNT(*) FROM policy q WHERE q.source = 'cleaneye' AND q.active = TRUE AND q.supervising_org = p.supervising_org) DESC, p.id
 LIMIT 1;
SQL
ID=$(sed -n 2p build/check-cleaneye-one.tsv | cut -f1)
sed -n 2p build/check-cleaneye-one.tsv | awk -F'\t' '{print "번호 " $2 " | " $3; print "신청하기(클린아이 원문): " $4; print "확인 필요: " $5}' | tee -a "$OUT"
curl -sS -m 10 "$BASE/api/v1/policies/$ID" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("supportContent"))' | tee -a "$OUT"
log "-- 같은 기관 카드 제목"
sql <<SQL | tee -a "$OUT"
SELECT title FROM policy WHERE source = 'cleaneye' AND active = TRUE
   AND supervising_org = (SELECT supervising_org FROM policy WHERE id = ${ID:-0}) ORDER BY title LIMIT 12;
SQL

log "== 9. 검색 (25세 · 서울) — 공공기관 채용 탭이 닫혀 있어 잡알리오 · 지방공공기관은 0건이어야 함"
curl -sS -m 30 -X POST "$BASE/api/v1/policies/match" -H 'Content-Type: application/json' \
  -d '{"age":25,"sido":"11"}' > build/check-cleaneye-match.json
python3 -c 'import collections,json; d=json.load(open("build/check-cleaneye-match.json")); print("전체", d["count"], dict(collections.Counter(p.get("source") for p in d["policies"])))' | tee -a "$OUT"

log "== 끝 ($(date '+%H:%M:%S')). 위 '신청하기' 주소를 브라우저로 열어 같은 공고인지 확인하십시오. 앱을 끕니다."
