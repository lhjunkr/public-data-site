#!/usr/bin/env bash
# DB 백업 (D-026). DB 서버의 ~/policy 에서 cron 이 매일 실행한다.
#   덤프 → 압축 → 내용 확인 → 7일치만 보관 → 앱 서버로 복사 → 어느 단계든 실패하면 디스코드 알림
# 시험: APP_HOST=ubuntu@10.0.0.1 ./backup.sh  → 복사 단계를 일부러 실패시켜 알림을 확인한다.
set -euo pipefail
umask 077

cd "$(dirname "$0")"

BACKUP_DIR="$HOME/backups/db"
KEEP_DAYS=7
APP_HOST="${APP_HOST:-ubuntu@10.0.0.78}"
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
docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction "$MYSQL_DATABASE"' \
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

echo "$(date '+%F %T') 백업 완료: $(basename "$FILE") ($(du -h "$FILE" | cut -f1))"
