"""새 데이터원 4개 응답 원본(samples/)의 칸별 분포를 센다 (12/5, docs/sources/*.md 의 숫자 출처).

사용: 리포 폴더에서  python3 scripts/analyze-samples.py [기준일 YYYY-MM-DD]
  원본은 scripts/fetch-samples.sh 로 받는다. 기준일(기본 2026-11-25 = 수집일)로 마감까지 남은 날을 계산한다.
  표준 라이브러리만 쓴다.
"""
import collections
import datetime
import glob
import json
import re
import sys
import xml.etree.ElementTree as ET

TODAY = datetime.date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else datetime.date(2026, 11, 25)
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
PHONE = re.compile(r"0\d{1,2}-\d{3,4}-\d{4}")
AGE = re.compile(r"만\s*\d{2}\s*세")


def ymd(s):
    s = s.replace("-", "")
    return datetime.date(int(s[:4]), int(s[4:6]), int(s[6:8]))


def left(s):
    n = (ymd(s) - TODAY).days
    if n < 0:
        return "마감 지남"
    if n <= 7:
        return "0~7일"
    if n <= 30:
        return "8~30일"
    if n <= 90:
        return "31~90일"
    if n <= 365:
        return "91일~1년"
    return "1년 넘음"


ORDER = ["마감 지남", "0~7일", "8~30일", "31~90일", "91일~1년", "1년 넘음"]


def show(title, counter, top=25):
    print(f"\n### {title} (서로 다른 값 {len(counter)}개)")
    for k, v in counter.most_common(top):
        print(f"  {v:5d}  {k}")


def show_left(title, values):
    c = collections.Counter(left(v) for v in values)
    print(f"\n### {title}")
    for k in ORDER:
        print(f"  {c.get(k, 0):5d}  {k}")


def split(v):
    return [t.strip() for t in (v or "").split(",") if t.strip()]


def multi(rows, key):
    c = collections.Counter()
    for r in rows:
        c.update(split(r.get(key)) or ["(빈 값)"])
    return c


def kstartup():
    ann = [d for f in sorted(glob.glob("samples/kstartup/announcement-*.json")) for d in json.load(open(f))["data"]]
    biz = [d for f in sorted(glob.glob("samples/kstartup/business-*.json")) for d in json.load(open(f))["data"]]
    print(f"\n## K-Startup 지원사업 공고(모집 중) {len(ann)}건 · 고유 pbanc_sn {len({a['pbanc_sn'] for a in ann})}")
    show("biz_trgt_age (대상 연령, 여러 값)", collections.Counter(a["biz_trgt_age"] for a in ann))
    show("biz_trgt_age 낱개", multi(ann, "biz_trgt_age"))
    show("supt_regin (지원 지역)", collections.Counter(a["supt_regin"] for a in ann))
    show("supt_biz_clsfc (지원 분야)", collections.Counter(a["supt_biz_clsfc"] for a in ann))
    show("biz_enyy 낱개 (업력)", multi(ann, "biz_enyy"))
    show("aply_trgt 낱개 (신청 대상)", multi(ann, "aply_trgt"))
    show("sprv_inst (주관 기관 유형)", collections.Counter(a["sprv_inst"] for a in ann))
    show_left("pbanc_rcpt_end_dt (접수 마감까지)", [a["pbanc_rcpt_end_dt"] for a in ann])
    print(f"\n  aply_trgt_ctnt 에 '만 NN세' 글: {sum(1 for a in ann if AGE.search(a.get('aply_trgt_ctnt') or ''))}건")
    print(f"  담당 전화(prch_cnpl_no) 있음: {sum(1 for a in ann if a.get('prch_cnpl_no'))}건")
    print(f"\n## K-Startup 통합공고 지원사업 {len(biz)}건 · biz_yr {dict(collections.Counter(b['biz_yr'] for b in biz))}")
    print(f"  칸: {sorted(biz[0].keys())}")


def myhome():
    def items(f):
        it = json.load(open(f))["response"]["body"].get("item") or []
        return it if isinstance(it, list) else [it]

    for name, label in [("rental", "공공임대"), ("sale", "공공분양")]:
        rows = [x for f in sorted(glob.glob(f"samples/myhome/{name}-*.json")) for x in items(f)]
        posts = {x["pblancId"]: x for x in rows}
        units = {(x["pblancId"], x["houseSn"]) for x in rows}
        print(f"\n## 마이홈 {label}: 행 {len(rows)} · 공고(pblancId) {len(posts)} · 공고+주택(pblancId, houseSn) {len(units)}")
        for k in ["sttusNm", "suplyInsttNm", "suplyTyNm", "houseTyNm", "brtcNm"]:
            if k in rows[0]:
                show(k, collections.Counter(x.get(k) for x in rows))
        print(f"\n  signguNm 빈 값: {sum(1 for x in rows if not x.get('signguNm'))}행")
        show_left("endDe (공고 단위, 접수 마감까지)", [x["endDe"] for x in posts.values()])
        print(f"  접수 시작 전(beginDe > 기준일) 공고: {sum(1 for x in posts.values() if ymd(x['beginDe']) > TODAY)}")


def alio():
    rows = [x for f in sorted(glob.glob("samples/alio/list-*.json")) for x in json.load(open(f))["result"]]
    print(f"\n## 잡알리오(진행 중) {len(rows)}건 · 고유 recrutPblntSn {len({r['recrutPblntSn'] for r in rows})} · 기관 {len({r['instNm'] for r in rows})}곳")
    for k in ["hireTypeNmLst", "workRgnNmLst", "acbgCondNmLst", "ncsCdNmLst"]:
        show(f"{k} 낱개 (여러 값 {sum(1 for r in rows if len(split(r.get(k))) > 1)}건)", multi(rows, k))
    show("recrutSeNm", collections.Counter(r["recrutSeNm"] for r in rows))
    show("replmprYn (대체인력)", collections.Counter(r["replmprYn"] for r in rows))
    show_left("pbancEndYmd (마감까지)", [r["pbancEndYmd"] for r in rows])
    text = lambda r: " ".join(str(r.get(k) or "") for k in ["aplyQlfcCn", "scrnprcdrMthdExpln", "prefCondCn", "prefCn"])
    print(f"\n  aplyQlfcCn 에 '만 NN세' 글: {sum(1 for r in rows if AGE.search(r.get('aplyQlfcCn') or ''))}건")
    print(f"  긴 글 4칸에 이메일: {sum(1 for r in rows if EMAIL.search(text(r)))}건 · 전화번호: {sum(1 for r in rows if PHONE.search(text(r)))}건")
    print(f"  scrnprcdrMthdExpln 최대 {max(len(r.get('scrnprcdrMthdExpln') or '') for r in rows)}자")


def local():
    rows, per = [], {}
    for f in sorted(glob.glob("samples/local/sido-007*.xml")):
        root = ET.parse(f).getroot()
        items = root.findall(".//item")
        per[f[-10:-4]] = (len(items), sorted({i.findtext("SIDO_CD") for i in items}), root.findtext(".//totalCount"))
        rows += [{c.tag: (c.text or "") for c in i} for i in items]
    print(f"\n## 지방공공기관 채용 {len(rows)}행 · 고유 NO {len({r['NO'] for r in rows})} · (기관, 제목) {len({(r['ENT_NAME'], r['ENT_TITLE']) for r in rows})}")
    print("\n### sidoCd → 시도 (항목 수, totalCount 값)")
    for k, v in per.items():
        print(f"  {k}  {v[0]:4d}  {', '.join(v[1]) or '(없음)'}  totalCount={v[2]}")
    for k in ["STATUS", "JOB_TYPE", "EMPLOY_GB", "ENT_GB", "ENT_KIND", "LOCAL_YN", "RE_MAN_YN", "RETIREMENT", "SPECIAL_ITEM", "YEARINCOME"]:
        show(k, collections.Counter(r.get(k) for r in rows), top=12)
    show_left("PUB_END_DATE (마감까지)", [r["PUB_END_DATE"] for r in rows])
    empty = lambda v: v in ("", "-", "null")
    for k in ["WORK_PLACE", "OFFICE_HOURS", "ADDRESS", "DUTY_DETAIL", "REFERENCE"]:
        print(f"  {k} 빈 값('', '-', 'null'): {sum(1 for r in rows if empty(r.get(k, '')))}/{len(rows)}")
    print(f"  어느 칸이든 이메일: {sum(1 for r in rows if any(EMAIL.search(v) for v in r.values()))}행")


if __name__ == "__main__":
    print(f"# 새 데이터원 분포 (기준일 {TODAY})")
    kstartup()
    myhome()
    alio()
    local()
