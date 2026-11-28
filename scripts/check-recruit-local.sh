#!/usr/bin/env bash
# 공공기관 채용 탭 로컬 확인 (12/15): 테스트 → 앱 실행 → 분류 API · 검색(서울 · 부산) · 상세 → 앱을 켜 둔 채 기다림
#
# 사용: 리포 폴더에서  bash scripts/check-recruit-local.sh
#   먼저 Docker(MySQL)가 떠 있어야 하고, 8080 에 다른 앱이 없어야 한다.
#   로컬 DB 에 잡알리오 · 지방공공기관 공고가 있어야 한다(11/28 check-alio-local.sh · check-cleaneye-local.sh 로 넣은 것).
#   끝나면 앱을 켜 둔다 → 브라우저로 http://localhost:8080 화면을 확인한 뒤 이 창에서 Ctrl+C 로 끈다.
#   결과는 화면과 build/check-recruit.txt 에 남는다(build/ 는 리포에 올라가지 않음).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/check-recruit.txt
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

log "== 3. 분류 API — 탭 6개, 마지막이 공공기관 채용(칩 5개)"
curl -sS -m 10 "$BASE/api/v1/categories" > build/check-recruit-categories.json
python3 - <<'PY' | tee -a "$OUT"
import json
tabs = json.load(open("build/check-recruit-categories.json"))
for t in tabs:
    print(f"{t['code']:<14} {t['name']}: " + " · ".join(c['name'] for c in t['chips']))
PY

log "== 4. 검색 — 화면과 같은 방식으로 탭 · 칩 건수를 센다"
for sido in 11 26; do
  curl -sS -m 30 -X POST "$BASE/api/v1/policies/match" -H 'Content-Type: application/json' \
    -d "{\"age\":25,\"sido\":\"$sido\"}" > "build/check-recruit-match-$sido.json"
  python3 - "$sido" <<'PY' | tee -a "$OUT"
import collections, json, sys
sido = sys.argv[1]
tabs = json.load(open("build/check-recruit-categories.json"))
d = json.load(open(f"build/check-recruit-match-{sido}.json"))
ps = d["policies"]
print(f"시도 {sido}: 전체 {d['count']} · 출처별 {dict(collections.Counter(p.get('source') for p in ps))}")
line = []
for t in tabs:
    n = sum(1 for p in ps if any(c.startswith(t['code'] + '.') for c in p.get('categories', [])))
    line.append(f"{t['name']} {n}")
print("  탭: " + " · ".join(line))
rec = [p for p in ps if any(c.startswith('recruit.') for c in p.get('categories', []))]
t = next(t for t in tabs if t['code'] == 'recruit')
print("  공공기관 채용 칩: 전체 " + str(len(rec)) + " · " + " · ".join(
    f"{c['name']} {sum(1 for p in rec if c['code'] in p['categories'])}" for c in t['chips']))
print(f"  고용형태 칩 없이 신입 가능만 있는 공고 {sum(1 for p in rec if p['categories'] == ['recruit.entry'])} · "
      f"비정규직 칩만 있는 공고(경력만 등) {sum(1 for p in rec if p['categories'] == ['recruit.contract'])}")
print(f"  잡알리오 · 지방 공고의 소득 판단: {dict(collections.Counter(p['incomeStatus'] for p in rec))} · "
      f"확인 필요 {sum(1 for p in rec if p.get('needsCheck'))} · 신청 주소 없음 {sum(1 for p in rec if not p.get('applyUrl'))}")
PY
done

log "== 5. 상세 — 서울 공공기관 채용 카드 중 잡알리오 1건 · 지방공공기관 1건"
for src in alio cleaneye; do
  ID=$(python3 -c "import json,sys; ps=json.load(open('build/check-recruit-match-11.json'))['policies']; print(next((p['id'] for p in ps if p.get('source')=='$src'), ''))")
  [ -z "$ID" ] && { log "-- $src: 서울 결과 없음"; continue; }
  python3 -c "import json; p=next(p for p in json.load(open('build/check-recruit-match-11.json'))['policies'] if p['id']==$ID); print('--', p['source'], p['id'], p['title']); print('   분류 칩', p['categories'], '| 원본', p.get('categoryMedium'), '|', p.get('applyPeriodText')); print('   신청', p.get('applyUrl'), '| 참고', p.get('refUrl'))" | tee -a "$OUT"
  curl -sS -m 10 "$BASE/api/v1/policies/$ID" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print("\n".join((d.get("supportContent") or "").splitlines()[:8]))' | tee -a "$OUT"
done

log "== 앱을 켜 둡니다 ($(date '+%H:%M:%S')). 화면 확인이 끝나면 이 창에서 Ctrl+C 로 끄십시오."
wait "$APP"
