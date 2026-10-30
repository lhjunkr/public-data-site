-- 원본 필드 길이 측정 (10/30 정규화 실패 대응). 문자 수 칸(VARCHAR · CHAR)에 들어가는 필드마다
-- 가장 긴 값과 칸 크기를 넘는 정책 수를 잰다. 읽기만 한다.
-- 대상: 마지막 성공 수집 이후 확인된 승인 정책 (= 정규화 대상 전체)

WITH base AS (
    SELECT raw_json FROM policy_raw
    WHERE fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS')
      AND JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = '0044002'
),
f AS (
    SELECT 'title'             AS col, 255 AS lim, CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.plcyNm'))         AS len FROM base
    UNION ALL SELECT 'category_large',    50,  CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.lclsfNm'))         FROM base
    UNION ALL SELECT 'category_medium',   50,  CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.mclsfNm'))         FROM base
    UNION ALL SELECT 'age_limit_yn',      1,   CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.sprtTrgtAgeLmtYn')) FROM base
    UNION ALL SELECT 'earn_cnd_se_cd',    7,   CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.earnCndSeCd'))     FROM base
    UNION ALL SELECT 'apply_period_text', 255, CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.aplyYmd'))         FROM base
    UNION ALL SELECT 'apply_url',         500, CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.aplyUrlAddr'))     FROM base
    UNION ALL SELECT 'ref_url',           500, CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.refUrlAddr1'))     FROM base
    UNION ALL SELECT 'supervising_org',   255, CHAR_LENGTH(JSON_UNQUOTE(raw_json->'$.sprvsnInstCdNm'))  FROM base
)
SELECT col, lim, MAX(len) AS max_len, SUM(IF(len > lim, 1, 0)) AS over_limit
FROM f
GROUP BY col, lim
ORDER BY over_limit DESC, col;

-- 콤마로 쪼갠 낱개 값: 지역 코드(region_code 50) · 취업 상태 코드(job_code 7)
WITH base AS (
    SELECT raw_json FROM policy_raw
    WHERE fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS')
      AND JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = '0044002'
)
SELECT 'region_code' AS col, 50 AS lim, MAX(CHAR_LENGTH(TRIM(j.v))) AS max_len, SUM(IF(CHAR_LENGTH(TRIM(j.v)) > 50, 1, 0)) AS over_limit
FROM base, JSON_TABLE(CONCAT('["', REPLACE(COALESCE(JSON_UNQUOTE(raw_json->'$.zipCd'), ''), ',', '","'), '"]'),
                      '$[*]' COLUMNS (v VARCHAR(500) PATH '$')) j
UNION ALL
SELECT 'job_code', 7, MAX(CHAR_LENGTH(TRIM(j.v))), SUM(IF(CHAR_LENGTH(TRIM(j.v)) > 7, 1, 0))
FROM base, JSON_TABLE(CONCAT('["', REPLACE(COALESCE(JSON_UNQUOTE(raw_json->'$.jobCd'), ''), ',', '","'), '"]'),
                      '$[*]' COLUMNS (v VARCHAR(500) PATH '$')) j;
