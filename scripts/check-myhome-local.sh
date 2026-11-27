#!/usr/bin/env bash
# 마이홈 로컬 확인 한 번에 (12/12): 테스트 → 앱 실행 → 마이홈 정규화 → 검색 · 상세 확인 → 앱 종료
#
# 사용: 리포 폴더에서  bash scripts/check-myhome-local.sh
#   먼저 Docker(MySQL)가 떠 있어야 하고, 8080 에 다른 앱이 없어야 한다(앱 창의 bootRun 은 Ctrl+C 로 끈다).
#   결과는 화면과 build/check-myhome.txt 에 남는다(build/ 는 리포에 올라가지 않음).
#   관리 토큰은 .env 에서 바로 헤더로 넘기고 화면 · 파일에 쓰지 않는다.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/check-myhome.txt
BASE=http://127.0.0.1:8080
mkdir -p build
: > "$OUT"
log() { echo "$*" | tee -a "$OUT"; }

if curl -s -o /dev/null -m 2 "$BASE/health"; then
  log "8080 에서 앱이 이미 돌고 있습니다. 앱 창에서 Ctrl+C 로 끈 뒤 다시 실행하십시오."
  exit 1
fi

log "== 1. 테스트 ($(date '+%H:%M:%S'))"
./gradlew test > build/check-test.log 2>&1
rc=$?
# 맥 기본 bash 3.2 는 "$( … )" 안의 여러 줄 · 따옴표를 잘못 읽는다(11/27 첫 실행에서 awk 오류). 그래서 결과를 파이프로 tee 한다
cat build/test-results/test/*.xml | grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  | awk -F'"' '{t+=$2; f+=$6; e+=$8} END {print "tests="t, "failures="f, "errors="e}' | tee -a "$OUT"
log "(gradle exit $rc)"
if [ "$rc" -ne 0 ]; then
  grep -E 'FAILED|Error|Exception' build/check-test.log | head -20 | tee -a "$OUT"
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

log "== 3. 마이홈 정규화 (오늘 받은 원본을 새 규칙으로 다시 옮김)"
grep '^ADMIN_TOKEN=' .env | sed 's/^ADMIN_TOKEN=/X-Admin-Token: /' \
  | curl -sS -m 120 -X POST "$BASE/api/v1/admin/normalize?source=myhome" -H @- | tee -a "$OUT"
echo | tee -a "$OUT"

log "== 4. 검색 (25세)"
for sido in 11 26; do
  curl -sS -m 30 -X POST "$BASE/api/v1/policies/match" -H 'Content-Type: application/json' \
    -d "{\"age\":25,\"sido\":\"$sido\"}" > "build/check-match-$sido.json"
  python3 - "$sido" <<'PY' | tee -a "$OUT"
import collections, json, sys
sido = sys.argv[1]
d = json.load(open(f"build/check-match-{sido}.json"))
mh = [p for p in d["policies"] if p.get("source") == "myhome"]
titles = collections.Counter(p["title"] for p in mh)
print(f"시도 {sido}: 전체 {d['count']} · 출처별 {dict(collections.Counter(p.get('source') for p in d['policies']))}")
print(f"  마이홈 {len(mh)}건 · 칩 {dict(collections.Counter(c for p in mh for c in p.get('categories', [])))} · 제목이 겹치는 카드 {sum(c for c in titles.values() if c > 1)}장")
for p in mh:
    print(f"  - [{p['id']}] {p['title']} | {p.get('categoryMedium')} | {p.get('applyPeriodText')}")
PY
done

log "== 5. 상세 (서울 마이홈 앞 3건의 지원 내용)"
for id in $(python3 -c 'import json; ids = [str(p["id"]) for p in json.load(open("build/check-match-11.json"))["policies"] if p.get("source") == "myhome"]; print(" ".join(ids[:3]))'); do
  log "--- id $id"
  curl -sS -m 10 "$BASE/api/v1/policies/$id" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("title")); print(d.get("supportContent"))' | tee -a "$OUT"
done

log "== 끝 ($(date '+%H:%M:%S')). 앱을 끕니다."
