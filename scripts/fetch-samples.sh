#!/usr/bin/env bash
# 새 데이터원 4개의 응답 원본을 표본으로 받는다 (12/5 필드 분포 측정용).
# 받은 파일은 samples/ 에 두고 리포에는 올리지 않는다(.gitignore). 분석 결과만 docs/sources/*.md 에 남긴다.
#
# 사용: 리포 폴더에서  bash scripts/fetch-samples.sh [kstartup] [myhome] [alio] [local] [lh]
#   소스를 적지 않으면 4개 모두. 적으면 그 소스만 다시 받는다. local-probe(요청 형식 시험) · lh(LH 공고문, 12/12 겹침 측정)는 적었을 때만 돈다.
#   공공데이터포털 "일반 인증키"(Decoding 값)를 물어본다. 입력은 화면 · 셸 기록에 남지 않는다.
#   키는 curl 이 URL 인코딩한다 → Encoding 값을 넣으면 두 번 인코딩되어 SERVICE_KEY_IS_NOT_REGISTERED.
#
# 11/25 첫 실행 결과 전체 건수가 작아(진행 중 K-Startup 175 · 통합공고 511 · 공공임대 347 · 공공분양 71 · 잡알리오 547)
#   100건 표본 대신 전부 받는다. 호출 수: K-Startup 8 · 마이홈 5 · 잡알리오 6 · 지방공공기관 20 = 39번 (일일 한도 1,000~10,000 안)
set -uo pipefail

cd "$(dirname "$0")/.."
OUT=samples
mkdir -p "$OUT/kstartup" "$OUT/myhome" "$OUT/alio" "$OUT/local"

read -rsp "공공데이터포털 일반 인증키(Decoding): " KEY
echo
if [ -z "$KEY" ]; then
  echo "키가 비어 있습니다"
  exit 1
fi

KS=https://apis.data.go.kr/B552735/kisedKstartupService02
MH=https://apis.data.go.kr/1613000/HWSPR02
AL=https://apis.data.go.kr/1051000/recruitment
LC=https://apis.data.go.kr/B551982/openApiEmployInfo
LH=https://apis.data.go.kr/B552555/lhLeaseNoticeInfo1

# fetch 저장파일 주소 이름=값...
#   값은 curl 이 URL 인코딩한다. 이미 %로 인코딩해 둔 항목(이름에 [ ] 가 있는 조건)은 그대로 붙인다.
#   결과: 파일 · HTTP 상태 · 크기 · 응답 앞부분(오류 원인 확인용)
fetch() {
  local out=$1 url=$2
  shift 2
  local args=()
  local kv
  for kv in "$@"; do
    case $kv in
      *%*) args+=(--data "$kv") ;;
      *) args+=(--data-urlencode "$kv") ;;
    esac
  done
  local code
  code=$(curl -sS -g -G --max-time 30 -o "$out" -w '%{http_code}' "$url" \
    --data-urlencode "serviceKey=$KEY" ${args[@]+"${args[@]}"}) || code="curl실패"
  local size
  size=$(wc -c < "$out" 2>/dev/null | tr -d ' ')
  printf '%-30s HTTP %-7s %8s bytes  %s\n' "$out" "$code" "${size:-0}" \
    "$(head -c 150 "$out" 2>/dev/null | LC_ALL=C tr -d '\n\r')"
  sleep 0.3
}

NSRC=$#
SOURCES=("$@")
explicit() {
  [ "$NSRC" -eq 0 ] && return 1
  local s
  for s in "${SOURCES[@]}"; do
    [ "$s" = "$1" ] && return 0
  done
  return 1
}
run() {
  [ "$NSRC" -eq 0 ] && return 0
  local s
  for s in "${SOURCES[@]}"; do
    [ "$s" = "$1" ] && return 0
  done
  return 1
}

if run kstartup; then
echo "== K-Startup (창업지원 공고 · 통합공고 사업)"
for pg in 1 2; do
  fetch "$OUT/kstartup/announcement-$pg.json" "$KS/getAnnouncementInformation02" \
    page=$pg perPage=100 returnType=json 'cond%5Brcrt_prgs_yn%3A%3AEQ%5D=Y'
done
for pg in 1 2 3 4 5 6; do
  fetch "$OUT/kstartup/business-$pg.json" "$KS/getBusinessInformation02" \
    page=$pg perPage=100 returnType=json
done
fi

if run myhome; then
echo "== 마이홈 (공공임대 · 공공분양 모집공고)"
for pg in 1 2 3 4; do
  fetch "$OUT/myhome/rental-$pg.json" "$MH/rsdtRcritNtcList" pageNo=$pg numOfRows=100
done
fetch "$OUT/myhome/sale-1.json" "$MH/ltRsdtRcritNtcList" pageNo=1 numOfRows=100
fi

if run alio; then
echo "== 잡알리오 (공공기관 채용, 진행 중인 공고만)"
for pg in 1 2 3 4 5 6; do
  fetch "$OUT/alio/list-$pg.json" "$AL/list" pageNo=$pg numOfRows=100 resultType=json ongoingYn=Y
done
fi

if run local; then
echo "== 지방공공기관 채용 (시도별)"
# sidoCd 는 행정구역 코드가 아니라 클린아이 자체 코드: 서울 = 007001 (포털 미리보기 주소에서 확인, 11/25).
#   나머지 시도의 번호는 공개 명세에 없어 007001 ~ 007020 을 차례로 부르고 응답의 SIDO_CD(시도 이름)로 확인한다.
#   미리보기 주소에 페이지 파라미터가 없어 보내지 않는다(시도별 전부가 한 번에 오는 것으로 보임 → 분석에서 확인).
#   응답의 totalCount 는 항목이 있어도 0 이라 믿지 않는다.
for n in 001 002 003 004 005 006 007 008 009 010 011 012 013 014 015 016 017 018 019 020; do
  fetch "$OUT/local/sido-007$n.xml" "$LC/openXmlEmployInfo" sidoCd="007$n" type=xml
done
fi

if explicit lh; then
echo "== LH 분양임대공고문 (12/12: 마이홈과 겹치는 비율 측정용)"
# 공고 상태로 나눠 받는다. 마이홈은 접수가 끝나지 않은 공고만 주므로 '공고중' · '접수중'과 비교한다.
#   한 번에 최대 1,000건(PG_SZ). 형식을 모르는 첫 호출이라 10건짜리 probe 도 따로 남긴다.
mkdir -p "$OUT/lh"
fetch "$OUT/lh/probe.json" "$LH/lhLeaseNoticeInfo1" PG_SZ=10 PAGE=1
fetch "$OUT/lh/notice-open.json" "$LH/lhLeaseNoticeInfo1" PG_SZ=1000 PAGE=1 PAN_SS=공고중
fetch "$OUT/lh/notice-receiving.json" "$LH/lhLeaseNoticeInfo1" PG_SZ=1000 PAGE=1 PAN_SS=접수중
# 11/27 확인: 기간을 안 주면 '오늘부터 2개월 전'까지 게시된 공고만 온다(응답 dsSch 의 PAN_ST_DT ~ PAN_ED_DT).
#   마이홈에는 2025년에 게시된 잔여세대 · 선착순 공고도 있어 게시일 2023-01-01 부터 넓혀 다시 받는다.
WIDE_FROM=20230101
TODAY=$(date +%Y%m%d)
fetch "$OUT/lh/notice-open-wide.json" "$LH/lhLeaseNoticeInfo1" PG_SZ=1000 PAGE=1 PAN_SS=공고중 \
  PAN_ST_DT=$WIDE_FROM PAN_ED_DT=$TODAY
fetch "$OUT/lh/notice-receiving-wide.json" "$LH/lhLeaseNoticeInfo1" PG_SZ=1000 PAGE=1 PAN_SS=접수중 \
  PAN_ST_DT=$WIDE_FROM PAN_ED_DT=$TODAY
fi

if explicit local-probe; then
echo "== 지방공공기관 요청 형식 시험 (11/25: 시도 2자리 코드로는 19곳 모두 '결과 없음')"
mkdir -p "$OUT/local-probe"
fetch "$OUT/local-probe/1-key-only.xml" "$LC/openXmlEmployInfo"
fetch "$OUT/local-probe/2-type-only.xml" "$LC/openXmlEmployInfo" type=xml
fetch "$OUT/local-probe/3-name-full.xml" "$LC/openXmlEmployInfo" type=xml sidoCd=서울특별시
fetch "$OUT/local-probe/4-name-short.xml" "$LC/openXmlEmployInfo" type=xml sidoCd=서울
fetch "$OUT/local-probe/5-inst-code.xml" "$LC/openXmlEmployInfo" type=xml sidoCd=6110000
fetch "$OUT/local-probe/6-code-no-paging.xml" "$LC/openXmlEmployInfo" type=xml sidoCd=11
fetch "$OUT/local-probe/7-legal-code.xml" "$LC/openXmlEmployInfo" type=xml sidoCd=1100000000
fi

unset KEY
echo "끝. 원본은 $OUT/ (리포에 올라가지 않음)"
