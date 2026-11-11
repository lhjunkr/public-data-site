#!/usr/bin/env python3
"""매칭 응답을 압축하면 몇 KB가 되는지, 전송에 왕복이 몇 번 드는지 미리 잰다 (D-027 예측 ② · ③).

사용: python3 scripts/gzip-size.py 응답.json
응답을 서버(Jackson)처럼 공백 없이 UTF-8 로 다시 쓴 뒤 gzip(수준 6)으로 압축한다.
"긴 글 제외"는 목록에서 supportContent 를 뺀 모양(개선 ②)이다.
묶음 수: 새 연결은 처음 약 14KB를 보내고 왕복마다 두 배로 늘린다고 보는 모델 A(docs/performance.md).
전송 예측 = (묶음 수 - 1) x 왕복 65ms x 1.08(Before 보정). Caddy 의 압축 수준과는 조금 다를 수 있다.
1KB = 1,000바이트 (measure.sh 와 같음).
"""
import gzip
import json
import sys

FIRST_FLIGHT = 14_480  # 처음 보내는 양: 패킷 10개 x 1,448바이트
RTT_MS = 65            # 맥 /health 의 TCP p50 (핫스팟)
CALIBRATION = 1.08     # Before 실측 280ms / 모델 260ms


def compact(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def flights(n_bytes):
    sent, size, count = 0, FIRST_FLIGHT, 0
    while sent < n_bytes:
        sent += size
        size *= 2
        count += 1
    return count


def transfer_ms(n_bytes):
    return (flights(n_bytes) - 1) * RTT_MS * CALIBRATION


with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)

no_content = dict(data)
no_content["policies"] = [
    {k: v for k, v in p.items() if k != "supportContent"} for p in data["policies"]
]

print(f"정책 {len(data['policies'])}건")
for label, obj in (("지금", data), ("긴 글 제외", no_content)):
    raw = compact(obj)
    gz = gzip.compress(raw, compresslevel=6, mtime=0)
    print(f"[{label}] 원래 {len(raw) / 1000:.1f}KB (묶음 {flights(len(raw))}, 전송 약 {transfer_ms(len(raw)):.0f}ms)"
          f" → gzip {len(gz) / 1000:.1f}KB · {len(gz) / len(raw) * 100:.0f}%"
          f" (묶음 {flights(len(gz))}, 전송 약 {transfer_ms(len(gz)):.0f}ms)")
