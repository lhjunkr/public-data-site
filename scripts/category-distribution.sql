-- 분류 분포 측정 (범위 확대 설계용). 마지막 성공 수집 이후 확인된 원본(policy_raw) 전체를 본다. 읽기만 한다.
-- 대분류 · 중분류에는 콤마 다중값이 있으므로 JSON_TABLE 로 낱개로 쪼개서 센다.

-- [1] 전체 / 승인된 정책 수
SELECT COUNT(*)                                                                      AS seen,
       SUM(IF(JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = '0044002', 1, 0))          AS approved
FROM policy_raw
WHERE fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS');

-- [2] 대분류 낱개별 승인 정책 수 (한 정책이 두 대분류에 걸치면 양쪽에 센다)
WITH base AS (
    SELECT policy_no, JSON_UNQUOTE(raw_json->'$.lclsfNm') AS l
    FROM policy_raw
    WHERE fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS')
      AND JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = '0044002'
)
SELECT TRIM(j.v) AS large, COUNT(DISTINCT b.policy_no) AS n
FROM base b,
     JSON_TABLE(CONCAT('["', REPLACE(COALESCE(b.l, ''), ',', '","'), '"]'), '$[*]' COLUMNS (v VARCHAR(100) PATH '$')) j
GROUP BY large
ORDER BY n DESC;

-- [3] 대분류 x 중분류 (낱개끼리). 두 세대 대분류 이름이 같은 중분류를 나눠 갖는지 본다
WITH base AS (
    SELECT policy_no,
           JSON_UNQUOTE(raw_json->'$.lclsfNm') AS l,
           JSON_UNQUOTE(raw_json->'$.mclsfNm') AS m
    FROM policy_raw
    WHERE fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS')
      AND JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = '0044002'
)
SELECT TRIM(jl.v) AS large, TRIM(jm.v) AS medium, COUNT(DISTINCT b.policy_no) AS n
FROM base b,
     JSON_TABLE(CONCAT('["', REPLACE(COALESCE(b.l, ''), ',', '","'), '"]'), '$[*]' COLUMNS (v VARCHAR(100) PATH '$')) jl,
     JSON_TABLE(CONCAT('["', REPLACE(COALESCE(b.m, ''), ',', '","'), '"]'), '$[*]' COLUMNS (v VARCHAR(100) PATH '$')) jm
GROUP BY large, medium
ORDER BY medium, large;

-- [4] 대분류 값이 콤마 다중값인 정책의 원문 조합 (어떤 조합이 있는지)
SELECT JSON_UNQUOTE(raw_json->'$.lclsfNm') AS large_raw, COUNT(*) AS n
FROM policy_raw
WHERE fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS')
  AND JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = '0044002'
  AND JSON_UNQUOTE(raw_json->'$.lclsfNm') LIKE '%,%'
GROUP BY large_raw
ORDER BY n DESC;
