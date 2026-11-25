#!/usr/bin/env bash
# 클립보드(맥)에 복사해 둔 API 주소에서 serviceKey 값만 XXX 로 가려 출력한다.
# 브라우저 주소창의 요청 주소를 채팅 · 문서에 옮길 때 키가 함께 나가지 않게 하려고 쓴다.
# 사용: 주소를 복사(Cmd+L → Cmd+C)한 뒤 터미널에 직접 타이핑:  bash scripts/mask-url.sh
pbpaste | sed -E 's/([sS]ervice[kK]ey=)[^&]*/\1XXX/'
echo
