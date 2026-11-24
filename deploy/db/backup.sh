#!/usr/bin/env bash
# DB 백업 (D-026). DB 서버의 ~/policy 에서 cron 이 매일 실행한다.
#   덤프 → 압축 → 내용 확인 → 7일치만 보관 → 앱 서버로 복사 → 외부(Backblaze B2, 암호화) 사본
#   → 어느 단계든 실패하면 디스코드 알림
# 시험: APP_HOST=ubuntu@10.0.0.1 ./backup.sh  → 복사 단계를 일부러 실패시켜 알림을 확인한다.
#
# 필요한 것 (12/4)
#   1) ~/policy/mysql-client.cnf : 컨테이너 안 /root/.my.cnf 로 들어가는 접속 정보(권한 600). 만드는 명령:
#        (umask 077; printf '[client]\nuser=root\npassword=%s\n' "$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)" > mysql-client.cnf)
#   2) rclone 의 offsite 리모트 : b2(Backblaze B2, 이 버킷 하나만 쓰는 읽기·쓰기 키) 위에
#      crypt(내용 · 이름 암호화)를 얹은 것. crypt 암호 2개와 B2 키는 서버가 사라져도 복원할 수 있도록
#      서버 밖(비밀번호 관리자)에 따로 보관한다.
set -euo pipefail
umask 077

cd "$(dirname "$0")"

BACKUP_DIR="$HOME/backups/db"
KEEP_DAYS=7
APP_HOST="${APP_HOST:-ubuntu@10.0.0.78}"
OFFSITE="${OFFSITE:-offsite:db}"
SSH_OPTS=(-i "$HOME/.ssh/backup_to_app" -o BatchMode=yes -o ConnectTimeout=5)
FILE="$BACKUP_DIR/policy-$(date +%Y%m%d-%H%M).sql.gz"
WEBHOOK=$(grep '^ALERT_WEBHOOK_URL=' .env | cut -d= -f2- || true)
STEP="준비"

# 실패 알림. 웹훅이 없으면 보내지 않는다. 알림이 실패해도 원래 오류로 끝난다.
alert() {
  [ -n "$WEBHOOK" ] || return 0
  curl -sS -m 5 -o /dev/null -H "Content-Type: application/json" \
    -d "{\"content\":\"[chajaga] DB 백업 실패 — ${STEP} 단계. DB 서버 ~/policy/backup.log 확인\"}" \
    "$WEBHOOK" || true
}
trap 'alert; rm -f "$FILE.tmp"' ERR

mkdir -p "$BACKUP_DIR"

STEP="덤프"
# 접속 정보는 컨테이너 안 /root/.my.cnf (mysql-client.cnf) 에서 읽는다. 비밀번호가 명령줄에 남지 않는다.
docker compose exec -T mysql sh -c \
  'exec mysqldump --single-transaction "$MYSQL_DATABASE"' \
  | gzip > "$FILE.tmp"

STEP="내용 확인"
gzip -t "$FILE.tmp"
zcat "$FILE.tmp" | tail -1 | grep -q "Dump completed"
mv "$FILE.tmp" "$FILE"

STEP="오래된 파일 정리"
find "$BACKUP_DIR" -name 'policy-*.sql.gz' -mtime +$((KEEP_DAYS - 1)) -delete

STEP="앱 서버로 복사"
scp -q "${SSH_OPTS[@]}" "$FILE" "$APP_HOST:backups/db/"
ssh "${SSH_OPTS[@]}" "$APP_HOST" "find ~/backups/db -name 'policy-*.sql.gz' -mtime +$((KEEP_DAYS - 1)) -delete"

STEP="외부 사본"
# 오라클 계정 · 리전 사고에도 남도록 다른 회사(Backblaze B2)에 둔다. crypt 리모트라 올라가는 것은 암호문뿐이다.
# 보관은 다른 사본과 같은 7일 (개인정보 처리방침 2항: 백업에 최대 7일).
# B2 는 지운 파일을 기본으로 "숨김"으로만 두므로 --b2-hard-delete 로 실제로 지운다.
rclone copy "$FILE" "$OFFSITE" --quiet
rclone delete "$OFFSITE" --min-age "${KEEP_DAYS}d" --b2-hard-delete --quiet

echo "$(date '+%F %T') 백업 완료: $(basename "$FILE") ($(du -h "$FILE" | cut -f1)) · 외부 사본 완료"
