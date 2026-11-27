"""LH 분양임대공고문과 마이홈 모집공고가 얼마나 겹치는지 센다 (12/12, D-029 ⑤ 'LH는 겹침 측정 후 결정').

사용: 리포 폴더에서  bash scripts/fetch-samples.sh myhome lh   (같은 시각에 두 소스를 받는다)
       python3 scripts/lh-overlap.py
  표준 라이브러리만 쓴다. 원본은 samples/ (리포에 올라가지 않음).

같은 공고의 기준: 마이홈 원본의 url 에 든 panId = LH 공고의 PAN_ID (같은 식별자일 때만, D-029 ⑤).
판단 기준(측정 전에 정함, docs/sources/lh.md): 마이홈에 없는 LH 주택 공고가 20건 이상이면서
  마이홈의 LH 공고 수의 10% 이상이면 LH 를 보조 소스로 붙인다(일정은 1월). 아니면 붙이지 않는다.
"""
import collections
import glob
import json
import re
import sys

NOT_HOUSING = {"01": "토지", "22": "상가"}  # 주택 공고가 아닌 상위 유형
MIN_ONLY = 20
MIN_RATIO = 0.10


def walk(node):
    """JSON 어디에 있든 PAN_ID 를 가진 객체를 모두 꺼낸다(응답 모양을 가정하지 않는다)."""
    if isinstance(node, dict):
        if "PAN_ID" in node or "pan_id" in node:
            yield node
        for v in node.values():
            yield from walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk(v)


def walk_key(node, key):
    """JSON 안의 key 목록 값(요청 조건 dsSch 등)을 꺼낸다."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k == key and isinstance(v, list):
                yield from (x for x in v if isinstance(x, dict))
            else:
                yield from walk_key(v, key)
    elif isinstance(node, list):
        for v in node:
            yield from walk_key(v, key)


def g(d, key):
    v = d.get(key)
    if v is None:
        v = d.get(key.lower())
    return "" if v is None else str(v).strip()


def myhome_rows():
    rows = []
    for name in ("rental", "sale"):
        for f in sorted(glob.glob(f"samples/myhome/{name}-*.json")):
            body = json.load(open(f, encoding="utf-8"))["response"]["body"]
            it = body.get("item") or []
            for r in (it if isinstance(it, list) else [it]):
                r["_kind"] = name
                rows.append(r)
    return rows


def main():
    files = sorted(glob.glob("samples/lh/notice-*.json"))
    if not files:
        sys.exit("samples/lh/notice-*.json 이 없습니다. 먼저 bash scripts/fetch-samples.sh myhome lh")

    lh = {}
    print("## LH 응답 파일")
    for f in files:
        try:
            data = json.load(open(f, encoding="utf-8"))
        except ValueError:
            print(f"  {f}: JSON 아님 → 앞부분 {open(f, encoding='utf-8', errors='replace').read(150)!r}")
            continue
        items = list(walk(data))
        all_cnt = {g(i, "ALL_CNT") for i in items if g(i, "ALL_CNT")}
        window = [f"{g(d, 'PAN_ST_DT')}~{g(d, 'PAN_ED_DT')}" for d in walk_key(data, "dsSch")]
        print(f"  {f}: 공고 {len(items)}건 · ALL_CNT {sorted(all_cnt) or '-'} · 게시일 범위 {window or '-'}")
        if all_cnt and max(int(c) for c in all_cnt) > len(items):
            print("    ※ ALL_CNT 가 받은 건수보다 많다 → PG_SZ · PAGE 를 늘려 다시 받아야 한다")
        for i in items:
            lh[g(i, "PAN_ID")] = i
    print(f"  서로 다른 PAN_ID {len(lh)}개")

    print("\n## LH 상위 유형 · 상태")
    print("  ", dict(collections.Counter(f"{g(i, 'UPP_AIS_TP_CD')} {g(i, 'UPP_AIS_TP_NM')}" for i in lh.values())))
    print("  ", dict(collections.Counter(g(i, "PAN_SS") for i in lh.values())))
    housing = {k: v for k, v in lh.items() if g(v, "UPP_AIS_TP_CD") not in NOT_HOUSING}
    print(f"  주택 공고(토지 · 상가 제외) {len(housing)}개")

    rows = myhome_rows()
    posts = {}
    for r in rows:
        posts.setdefault((r["_kind"], str(r.get("pblancId"))), r)
    pan = {}
    for (kind, pid), r in posts.items():
        m = re.search(r"[?&]panId=(\d+)", r.get("url") or "")
        if m:
            pan[m.group(1)] = r
    print(f"\n## 마이홈: 행 {len(rows)} · 공고 {len(posts)} · url 에 panId 가 있는 공고 {len(pan)}")
    no_pan = collections.Counter(r.get("suplyInsttNm") for r in posts.values()
                                 if not re.search(r"[?&]panId=", r.get("url") or ""))
    print(f"  panId 없는 공고(LH 가 아닌 기관): {dict(no_pan)}")

    both = set(housing) & set(pan)
    lh_only = sorted(set(housing) - set(pan))
    myhome_only = sorted(set(pan) - set(lh))
    print("\n## 겹침")
    print(f"  LH 주택 공고 {len(housing)} 중 마이홈에도 있음 {len(both)} "
          f"({len(both) / len(housing):.0%})" if housing else "  LH 주택 공고 0")
    print(f"  LH 에만 있음 {len(lh_only)} · 마이홈(LH 공고)에만 있음 {len(myhome_only)}")
    print("  LH 에만 있는 공고의 유형:",
          dict(collections.Counter(f"{g(housing[k], 'UPP_AIS_TP_NM')}/{g(housing[k], 'AIS_TP_CD_NM')}" for k in lh_only)))
    for k in lh_only[:40]:
        i = housing[k]
        print(f"    {k} | {g(i, 'UPP_AIS_TP_NM')}/{g(i, 'AIS_TP_CD_NM')} | {g(i, 'CNP_CD_NM')} | {g(i, 'PAN_SS')} | "
              f"{g(i, 'PAN_NT_ST_DT')} ~ {g(i, 'CLSG_DT')} | {g(i, 'PAN_NM')}")
    if myhome_only:
        print("  마이홈에만 있는 LH 공고(앞 10개):")
        for k in myhome_only[:10]:
            r = pan[k]
            print(f"    {k} | {r.get('sttusNm')} | {r.get('beginDe')} ~ {r.get('endDe')} | {r.get('pblancNm')}")

    base = len(pan)
    ok = len(lh_only) >= MIN_ONLY and base and len(lh_only) / base >= MIN_RATIO
    print("\n## 판단 (측정 전 기준: LH 에만 있는 주택 공고 ≥ "
          f"{MIN_ONLY}건 그리고 ≥ 마이홈 LH 공고의 {MIN_RATIO:.0%})")
    print(f"  LH 에만 있음 {len(lh_only)}건 / 마이홈 LH 공고 {base}건 = "
          f"{(len(lh_only) / base if base else 0):.1%} → {'LH 를 보조로 붙인다(1월)' if ok else 'LH 를 붙이지 않는다'}")


if __name__ == "__main__":
    main()
