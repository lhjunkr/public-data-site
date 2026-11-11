#!/usr/bin/env bash
# 지역 확인 방식별 매칭 SQL 시간 · 결과 건수 (11/21 · D-027 개선 1 실험)
# 사용 (DB 서버 ~/policy, docker-compose.yml 이 있는 곳): ./explain-region.sh 25:11 34:11
#   인자 하나 = 나이:시도. 조건마다 explain-region.sql 을 3번 돌려, 방식별 이름표와 맨 윗줄(전체 시간 · 결과 건수)만 보여 준다.
#   @age · @sido 같은 자리를 글자 그대로의 값('11' 등)으로 바꿔 넣는다. 앱(Connector/J 기본 설정)도 값을 SQL 글자에 넣어 보낸다.
#   @sido_next 는 시도 코드 + 1 (예: '11' → '12'). @sido 보다 먼저 바꿔야 '@sido' 부분만 바뀌는 일이 없다.
#   오류가 나면 ERROR 줄이 그대로 보인다.
set -u
cd "$(dirname "$0")"
for cond in "$@"; do
  age="${cond%%:*}"
  sido="${cond##*:}"
  next=$(printf '%02d' $((10#$sido + 1)))
  echo "===== age ${age} / sido ${sido} ====="
  for i in 1 2 3; do
    sed -e "s/@sido_next/'${next}'/g" -e "s/@sido/'${sido}'/g" -e "s/@age/${age}/g" -e "s/@income/NULL/g" -e "s/@job/NULL/g" explain-region.sql \
      | docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -r -N "$MYSQL_DATABASE"' 2>&1 \
      | grep -E '^(\[|-> |ERROR)'
  done
done
