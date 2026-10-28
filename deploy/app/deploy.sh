#!/usr/bin/env bash
set -euo pipefail

TAG="${1:?사용법: ./deploy.sh <이미지 태그>}"
if [[ ! "$TAG" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "잘못된 태그: $TAG"
  exit 1
fi

cd "$(dirname "$0")"

PREV=$(grep '^APP_TAG=' .env | cut -d= -f2)
echo "배포 시작: ${PREV} -> ${TAG}"

sed -i "s|^APP_TAG=.*|APP_TAG=${TAG}|" .env
docker compose pull -q app
docker compose up -d app

for _ in $(seq 1 20); do
  if curl -fs localhost:8080/health > /dev/null; then
    echo "배포 성공: ${TAG}"
    exit 0
  fi
  sleep 5
done

echo "배포 실패: 100초 안에 /health 응답 없음. 되돌리기: ./deploy.sh ${PREV}"
exit 1
