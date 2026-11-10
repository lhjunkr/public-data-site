#!/usr/bin/env bash
# 응답 시간 측정 (11/16 · D-027 근거). 같은 요청을 N번 보내 구간별 중앙값(p50) · p95 를 밀리초로 낸다.
#   사용: scripts/measure.sh <URL> [요청 본문 JSON 파일] [횟수, 기본 30]
#   본문 파일을 주면 POST(JSON), 안 주면 GET.
#   예: scripts/measure.sh https://chajaga.com/api/v1/policies/match scripts/match-25-seoul.json
# 요청마다 curl 을 새로 실행하므로 매번 새로 연결한다 = 처음 방문한 사용자의 시간.
# --compressed: 브라우저처럼 "압축해서 보내도 된다"고 알린다. 크기는 실제로 받은 바이트(압축됐으면 압축된 크기), 1KB = 1,000바이트.
set -euo pipefail

URL="${1:?사용: scripts/measure.sh <URL> [본문.json] [횟수]}"
BODY="${2:-}"
N="${3:-30}"
RAW=$(mktemp)
trap 'rm -f "$RAW"' EXIT

FMT='%{http_code} %{time_namelookup} %{time_connect} %{time_appconnect} %{time_starttransfer} %{time_total} %{size_download}\n'
ARGS=(-sS --compressed -o /dev/null -w "$FMT")
if [ -n "$BODY" ]; then
  ARGS+=(-H "Content-Type: application/json" --data-binary "@$BODY")
fi

for _ in $(seq "$N"); do
  curl "${ARGS[@]}" "$URL" >> "$RAW"
done

# curl 의 시각은 모두 "요청 시작부터 그 순간까지" 누적이라, 앞 시각을 빼서 구간을 만든다.
#   열: 코드 DNS TCP TLS SERVER TRANSFER TOTAL 크기
#   http 라면 TLS 가 없어 time_appconnect 가 0 이므로 TLS = 0, 연결 준비 완료 = TCP 연결 완료.
#   소수 6자리로 고정해 찍는다. 그냥 print 하면 0.0001 미만이 6e-05 처럼 찍히고 sort -n 이 이를 6 으로 읽어 순서가 틀어진다.
SEG=$(awk '{
  ready = ($4 > 0) ? $4 : $3
  tls   = ($4 > 0) ? $4 - $3 : 0
  printf "%s %.6f %.6f %.6f %.6f %.6f %.6f %s\n", $1, $2, $3 - $2, tls, $5 - ready, $6 - $5, $6, $7
}' "$RAW")

# 열 하나를 작은 순으로 정렬해 순위 방식으로 고른다: p50 = ceil(n×0.5)번째, p95 = ceil(n×0.95)번째.
pick() {
  echo "$SEG" | awk -v c="$1" '{ print $c }' | sort -n | awk -v scale="$2" '
    { v[NR] = $1 }
    END {
      m = int((NR + 1) / 2)
      p = int(NR * 0.95); if (p < NR * 0.95) p++
      printf "%9.1f %9.1f", v[m] * scale, v[p] * scale
    }'
}

echo "$URL  (${N}회, 요청마다 새 연결)"
echo "응답 코드: $(echo "$SEG" | awk '{ print $1 }' | sort | uniq -c | awk '{ printf "%s x %s  ", $2, $1 }')"
printf '%-9s %9s %9s\n' "" "p50" "p95"
printf '%-9s %s  %s\n' "DNS"      "$(pick 2 1000)" "ms  이름 → IP 주소"
printf '%-9s %s  %s\n' "TCP"      "$(pick 3 1000)" "ms  연결 (≈ 왕복 1번)"
printf '%-9s %s  %s\n' "TLS"      "$(pick 4 1000)" "ms  암호화 연결 (http 면 0)"
printf '%-9s %s  %s\n' "SERVER"   "$(pick 5 1000)" "ms  요청 → 첫 바이트 (서버 처리 + 왕복 1번)"
printf '%-9s %s  %s\n' "TRANSFER" "$(pick 6 1000)" "ms  첫 바이트 → 마지막 바이트"
printf '%-9s %s  %s\n' "TOTAL"    "$(pick 7 1000)" "ms  전체"
printf '%-9s %s  %s\n' "SIZE"     "$(pick 8 0.001)" "KB  받은 크기"
echo "※ 구간별 p50 을 더해도 TOTAL p50 과 꼭 같지는 않다(중앙값은 더할 수 없다)."
