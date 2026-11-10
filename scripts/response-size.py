#!/usr/bin/env python3
"""매칭 응답 크기를 항목(필드)별로 나눈다 (11/17 · D-027 근거).

사용: python3 scripts/response-size.py 응답.json
응답을 서버(Jackson)처럼 공백 없이 UTF-8 로 다시 쓰고, 정책마다 "키":값 이 차지하는 바이트를 필드별로 더한다.
한글은 UTF-8 에서 1글자 = 3바이트다. 1KB = 1,000바이트 (measure.sh 와 같음).
"""
import json
import os
import sys


def size(obj):
    return len(json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    data = json.load(f)

policies = data["policies"]
n = len(policies)
total = size(data)

by_key = {}
for p in policies:
    for key, value in p.items():
        by_key[key] = by_key.get(key, 0) + size(key) + 1 + size(value)

print(f"정책 {n}건 · 받은 파일 {os.path.getsize(path) / 1000:.1f}KB · 다시 쓴 크기 {total / 1000:.1f}KB")
print(f"{'field':<16}{'KB':>8}{'share':>8}{'avg_bytes':>11}")
for key, b in sorted(by_key.items(), key=lambda kv: -kv[1]):
    print(f"{key:<16}{b / 1000:>8.1f}{b / total * 100:>7.1f}%{b / n:>11.0f}")
other = total - sum(by_key.values())
print(f"{'(punctuation)':<16}{other / 1000:>8.1f}{other / total * 100:>7.1f}%")
