-- 지역 확인 방식 비교 (11/21 · D-027 개선 1 실험)
-- [A] 지금 앱의 MATCH_SQL 과 같은 모양: region 표와 조인해 시도를 확인한다 (scripts/explain-match.sql 과 같은 SQL)
-- [B] 쿼리 모양 바꾸기: region 표 없이 policy_region.region_code 앞 두 자리로 바로 확인한다 (LIKE '11%')
-- [C] 쿼리 모양 바꾸기: [B] 와 같은 뜻을 범위로 쓴다 ('11' 이상 '12' 미만)
--     같은 결과인 이유: 정규화가 region.sido 를 지역 코드 앞 두 자리로 만들고(PolicyNormalizer),
--     policy_region.region_code 는 외래 키라 region 에 없는 코드가 없다.
-- @age · @sido · @sido_next · @income · @job 자리는 explain-region.sh 가 글자 그대로의 값으로 바꿔 넣는다.
--   앱(Connector/J 기본 설정)도 값을 SQL 글자에 넣어 보내므로 같은 조건이 된다.
--   11/21 첫 측정은 MySQL 변수(SET @sido = '11')로 재서 앱과 조건이 달랐다.
-- 맨 윗줄 "-> ... (actual time=시작..끝 rows=건수 loops=1)" 에서 끝 값이 전체 ms, rows 가 결과 건수다.
-- 이름표를 영어로 둔 이유: 컨테이너 안 mysql 클라이언트 기본 글자 체계가 latin1 이라 한글이 ? 로 나온다.

SELECT '[A] now: join region';
EXPLAIN ANALYZE
SELECT p.id, p.title, p.category_large, p.category_medium,
       p.support_content, p.apply_period_text, p.apply_url, p.ref_url,
       p.earn_cnd_se_cd, p.earn_etc_cn, p.extra_qualification,
       CASE
           WHEN p.earn_cnd_se_cd = '0043001'                              THEN 'MET'
           WHEN p.earn_cnd_se_cd IS NULL OR p.earn_cnd_se_cd <> '0043002' THEN 'UNKNOWN'
           WHEN COALESCE(p.earn_max_amt, 0) = 0                           THEN 'UNKNOWN'
           WHEN @income IS NULL                                           THEN 'UNKNOWN'
           WHEN @income > p.earn_max_amt                                  THEN 'NOT_MET'
           WHEN COALESCE(p.earn_min_amt, 0) > 0                           THEN 'UNKNOWN'
           ELSE                                                                'MET'
           END AS income_status
FROM policy p
WHERE p.active = TRUE
  AND ( ( (p.min_age IS NULL OR p.min_age = 0 OR p.min_age <= @age)
    AND (p.max_age IS NULL OR p.max_age = 0 OR @age <= p.max_age) )
    OR (p.max_age > 0 AND p.min_age > p.max_age) )
  AND ( p.apply_end_date IS NULL OR p.apply_end_date >= CURDATE() )
  AND EXISTS (
    SELECT 1 FROM policy_region pr
                      JOIN region r ON r.code = pr.region_code
    WHERE pr.policy_id = p.id AND r.sido = @sido )
  AND EXISTS (
    SELECT 1 FROM policy_category pc
    WHERE pc.policy_id = p.id AND pc.category_code IN (
                                                       'housing.rent', 'housing.house',
                                                       'welfare.living', 'welfare.health', 'welfare.culture',
                                                       'education.tuition', 'education.skills',
                                                       'participation.youth', 'participation.exchange',
                                                       'jobs.employment', 'jobs.startup', 'jobs.worker') )
  AND ( @job IS NULL
    OR EXISTS (
        SELECT 1 FROM policy_job pj
        WHERE pj.policy_id = p.id AND pj.job_code IN ('0013010', @job) ) )
ORDER BY p.view_count DESC, p.id;

SELECT '[B] prefix: no region join';
EXPLAIN ANALYZE
SELECT p.id, p.title, p.category_large, p.category_medium,
       p.support_content, p.apply_period_text, p.apply_url, p.ref_url,
       p.earn_cnd_se_cd, p.earn_etc_cn, p.extra_qualification,
       CASE
           WHEN p.earn_cnd_se_cd = '0043001'                              THEN 'MET'
           WHEN p.earn_cnd_se_cd IS NULL OR p.earn_cnd_se_cd <> '0043002' THEN 'UNKNOWN'
           WHEN COALESCE(p.earn_max_amt, 0) = 0                           THEN 'UNKNOWN'
           WHEN @income IS NULL                                           THEN 'UNKNOWN'
           WHEN @income > p.earn_max_amt                                  THEN 'NOT_MET'
           WHEN COALESCE(p.earn_min_amt, 0) > 0                           THEN 'UNKNOWN'
           ELSE                                                                'MET'
           END AS income_status
FROM policy p
WHERE p.active = TRUE
  AND ( ( (p.min_age IS NULL OR p.min_age = 0 OR p.min_age <= @age)
    AND (p.max_age IS NULL OR p.max_age = 0 OR @age <= p.max_age) )
    OR (p.max_age > 0 AND p.min_age > p.max_age) )
  AND ( p.apply_end_date IS NULL OR p.apply_end_date >= CURDATE() )
  AND EXISTS (
    SELECT 1 FROM policy_region pr
    WHERE pr.policy_id = p.id AND pr.region_code LIKE CONCAT(@sido, '%') )
  AND EXISTS (
    SELECT 1 FROM policy_category pc
    WHERE pc.policy_id = p.id AND pc.category_code IN (
                                                       'housing.rent', 'housing.house',
                                                       'welfare.living', 'welfare.health', 'welfare.culture',
                                                       'education.tuition', 'education.skills',
                                                       'participation.youth', 'participation.exchange',
                                                       'jobs.employment', 'jobs.startup', 'jobs.worker') )
  AND ( @job IS NULL
    OR EXISTS (
        SELECT 1 FROM policy_job pj
        WHERE pj.policy_id = p.id AND pj.job_code IN ('0013010', @job) ) )
ORDER BY p.view_count DESC, p.id;

SELECT '[C] range: no region join';
EXPLAIN ANALYZE
SELECT p.id, p.title, p.category_large, p.category_medium,
       p.support_content, p.apply_period_text, p.apply_url, p.ref_url,
       p.earn_cnd_se_cd, p.earn_etc_cn, p.extra_qualification,
       CASE
           WHEN p.earn_cnd_se_cd = '0043001'                              THEN 'MET'
           WHEN p.earn_cnd_se_cd IS NULL OR p.earn_cnd_se_cd <> '0043002' THEN 'UNKNOWN'
           WHEN COALESCE(p.earn_max_amt, 0) = 0                           THEN 'UNKNOWN'
           WHEN @income IS NULL                                           THEN 'UNKNOWN'
           WHEN @income > p.earn_max_amt                                  THEN 'NOT_MET'
           WHEN COALESCE(p.earn_min_amt, 0) > 0                           THEN 'UNKNOWN'
           ELSE                                                                'MET'
           END AS income_status
FROM policy p
WHERE p.active = TRUE
  AND ( ( (p.min_age IS NULL OR p.min_age = 0 OR p.min_age <= @age)
    AND (p.max_age IS NULL OR p.max_age = 0 OR @age <= p.max_age) )
    OR (p.max_age > 0 AND p.min_age > p.max_age) )
  AND ( p.apply_end_date IS NULL OR p.apply_end_date >= CURDATE() )
  AND EXISTS (
    SELECT 1 FROM policy_region pr
    WHERE pr.policy_id = p.id AND pr.region_code >= @sido AND pr.region_code < @sido_next )
  AND EXISTS (
    SELECT 1 FROM policy_category pc
    WHERE pc.policy_id = p.id AND pc.category_code IN (
                                                       'housing.rent', 'housing.house',
                                                       'welfare.living', 'welfare.health', 'welfare.culture',
                                                       'education.tuition', 'education.skills',
                                                       'participation.youth', 'participation.exchange',
                                                       'jobs.employment', 'jobs.startup', 'jobs.worker') )
  AND ( @job IS NULL
    OR EXISTS (
        SELECT 1 FROM policy_job pj
        WHERE pj.policy_id = p.id AND pj.job_code IN ('0013010', @job) ) )
ORDER BY p.view_count DESC, p.id;

-- [D] 시도 단위로 저장했다고 치고 재 본다: policy_region 을 시도로 줄인 임시 표를 이 접속 안에서만 만든다.
--     TEMPORARY TABLE 은 이 접속에서만 보이고 접속이 끝나면 사라진다. 운영 표는 바뀌지 않는다.
SELECT '[D] sido table (temporary)';
CREATE TEMPORARY TABLE tmp_policy_sido (
    policy_id BIGINT  NOT NULL,
    sido      CHAR(2) NOT NULL,
    PRIMARY KEY (policy_id, sido)
) SELECT DISTINCT policy_id, LEFT(region_code, 2) AS sido FROM policy_region;
EXPLAIN ANALYZE
SELECT p.id, p.title, p.category_large, p.category_medium,
       p.support_content, p.apply_period_text, p.apply_url, p.ref_url,
       p.earn_cnd_se_cd, p.earn_etc_cn, p.extra_qualification,
       CASE
           WHEN p.earn_cnd_se_cd = '0043001'                              THEN 'MET'
           WHEN p.earn_cnd_se_cd IS NULL OR p.earn_cnd_se_cd <> '0043002' THEN 'UNKNOWN'
           WHEN COALESCE(p.earn_max_amt, 0) = 0                           THEN 'UNKNOWN'
           WHEN @income IS NULL                                           THEN 'UNKNOWN'
           WHEN @income > p.earn_max_amt                                  THEN 'NOT_MET'
           WHEN COALESCE(p.earn_min_amt, 0) > 0                           THEN 'UNKNOWN'
           ELSE                                                                'MET'
           END AS income_status
FROM policy p
WHERE p.active = TRUE
  AND ( ( (p.min_age IS NULL OR p.min_age = 0 OR p.min_age <= @age)
    AND (p.max_age IS NULL OR p.max_age = 0 OR @age <= p.max_age) )
    OR (p.max_age > 0 AND p.min_age > p.max_age) )
  AND ( p.apply_end_date IS NULL OR p.apply_end_date >= CURDATE() )
  AND EXISTS (
    SELECT 1 FROM tmp_policy_sido ps
    WHERE ps.policy_id = p.id AND ps.sido = @sido )
  AND EXISTS (
    SELECT 1 FROM policy_category pc
    WHERE pc.policy_id = p.id AND pc.category_code IN (
                                                       'housing.rent', 'housing.house',
                                                       'welfare.living', 'welfare.health', 'welfare.culture',
                                                       'education.tuition', 'education.skills',
                                                       'participation.youth', 'participation.exchange',
                                                       'jobs.employment', 'jobs.startup', 'jobs.worker') )
  AND ( @job IS NULL
    OR EXISTS (
        SELECT 1 FROM policy_job pj
        WHERE pj.policy_id = p.id AND pj.job_code IN ('0013010', @job) ) )
ORDER BY p.view_count DESC, p.id;
