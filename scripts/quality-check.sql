-- 데이터 품질 측정 (10/13~). 정규화 직후 서비스 대상(active = TRUE) 정책의 상태를 잰다.
-- 읽기만 한다(SELECT). 몇 번을 돌려도 DB 는 바뀌지 않는다.
--
-- 실행 (맥에서, DB 서버로):
--   ssh -i ~/.ssh/oracle-db.key ubuntu@168.110.46.199 \
--     'cd ~/policy && docker compose exec -T mysql mysql --default-character-set=utf8mb4 -t \
--      -upolicy -p"$(grep ^MYSQL_PASSWORD= .env | cut -d= -f2-)" policy' < scripts/quality-check.sql

-- [0] 수집 기록 최근 3회 (D-017)와, 마지막 성공 수집 이후 확인된 원본 수 (= 정규화 선정 후보의 모집단)
SELECT id, started_at, status, tot_count, items_seen, LEFT(message, 60) AS message
FROM collect_run
ORDER BY id DESC
LIMIT 3;

SELECT COUNT(*)                                                                                  AS raw_rows,
       SUM(IF(fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS'), 1, 0)) AS seen_since_last_success
FROM policy_raw;

-- [1] 건수: 활성(서비스 대상) / 비활성 (D-014)
SELECT SUM(active)     AS active,
       SUM(NOT active) AS inactive,
       COUNT(*)        AS total
FROM policy;

-- [2] 화면 분류(탭.칩)별 활성 정책 수 (D-018). 한 정책이 여러 칩에 속하면 각각 센다. jobs.* 는 닫힌 탭
SELECT pc.category_code, COUNT(*) AS n
FROM policy_category pc
JOIN policy p ON p.id = pc.policy_id
WHERE p.active = TRUE
GROUP BY pc.category_code
ORDER BY pc.category_code;

-- [3] 마감 상태. "상시로 처리됨"은 신청기간 글이 있는데 날짜 두 개를 못 찾은 정책이다
--     (PolicyNormalizer 의 PERIOD 패턴 불일치). 마감됐어도 결과에 나올 수 있다.
SELECT CASE
         WHEN apply_end_date IS NULL AND apply_period_text IS NULL THEN '1 상시 (기간 글 없음)'
         WHEN apply_end_date IS NULL                              THEN '2 상시로 처리됨 (기간 글 해석 실패)'
         WHEN apply_end_date >= CURDATE()                         THEN '3 진행중'
         ELSE                                                          '4 만료'
       END                                                  AS deadline_state,
       COUNT(*)                                             AS n,
       ROUND(COUNT(*) * 100 / SUM(COUNT(*)) OVER (), 1)     AS pct
FROM policy
WHERE active = TRUE
GROUP BY deadline_state
ORDER BY deadline_state;

-- [3-1] 해석 실패한 신청기간 원문 예시 (최대 10건)
SELECT policy_no, LEFT(apply_period_text, 60) AS period_text
FROM policy
WHERE active = TRUE AND apply_end_date IS NULL AND apply_period_text IS NOT NULL
ORDER BY policy_no
LIMIT 10;

-- [4] 나이: 제한 여부 x 0/0 교차표. "'Y' = 나이 제한 없음"(9/25, 30건 기준 추정)이 전체에서도 맞는지 본다.
--     Y 행의 has_range, N 행의 zero_zero 가 0 이 아니면 추정에 예외가 있다는 뜻이다.
SELECT age_limit_yn,
       COUNT(*)                                          AS n,
       SUM(IF(min_age = 0 AND max_age = 0, 1, 0))       AS zero_zero,
       SUM(IF(NOT (min_age = 0 AND max_age = 0), 1, 0)) AS has_range,
       SUM(IF(min_age > max_age, 1, 0))                 AS min_gt_max,
       SUM(IF(min_age IS NULL OR max_age IS NULL, 1, 0)) AS null_age
FROM policy
WHERE active = TRUE
GROUP BY age_limit_yn
ORDER BY age_limit_yn;

-- [5] 소득 조건 코드 분포. NULL 과 0043003 은 판단불가가 된다(D-002).
--     cmp_no_amount: 금액비교(0043002)인데 금액이 둘 다 없음 → 매칭 SQL 에서 누구나 "충족"이 된다.
--     cmp_max_zero : 금액비교인데 상한이 0 → 소득 0 외에는 전부 "미충족"이 된다.
SELECT COALESCE(earn_cnd_se_cd, 'NULL')                                       AS earn_cd,
       COUNT(*)                                                               AS n,
       ROUND(COUNT(*) * 100 / SUM(COUNT(*)) OVER (), 1)                       AS pct,
       SUM(IF(earn_cnd_se_cd = '0043002' AND earn_min_amt IS NULL AND earn_max_amt IS NULL, 1, 0)) AS cmp_no_amount,
       SUM(IF(earn_cnd_se_cd = '0043002' AND earn_max_amt = 0, 1, 0))       AS cmp_max_zero
FROM policy
WHERE active = TRUE
GROUP BY earn_cd
ORDER BY n DESC;

-- [5-1] 금액비교 정책의 금액 범위. 소득 단위(만원인가 원인가, 미결 #3) 판단 재료
SELECT COUNT(*)          AS n,
       MIN(earn_min_amt) AS min_of_min,
       MAX(earn_min_amt) AS max_of_min,
       MIN(earn_max_amt) AS min_of_max,
       MAX(earn_max_amt) AS max_of_max
FROM policy
WHERE active = TRUE AND earn_cnd_se_cd = '0043002';

-- [6] 지역 연결이 0건인 정책. 어느 시도를 골라도 결과에 나오지 않는다.
SELECT COUNT(*) AS no_region
FROM policy p
WHERE p.active = TRUE
  AND NOT EXISTS (SELECT 1 FROM policy_region pr WHERE pr.policy_id = p.id);

-- [7] "확인 필요" 뱃지 비율 (추가 자격 원문 있음, D-003)
SELECT SUM(extra_qualification IS NOT NULL)                              AS needs_check,
       COUNT(*)                                                          AS n,
       ROUND(SUM(extra_qualification IS NOT NULL) * 100 / COUNT(*), 1)   AS pct
FROM policy
WHERE active = TRUE;

-- [8] 품질 기준 (10/14, D-015 · D-016). age_never_match 와 amount_in_won 이 0 이어야 통과한다.
--     age_never_match: 매칭 SQL(PolicyMatchRepository)의 나이 규칙으로 0~120세 어느 나이에도 걸리지 않는 정책
--     amount_in_won  : 만원으로 바뀌지 않고 원 단위(10만 이상)로 남은 금액
--     cmp_unknown    : 금액비교 정책 중 상한이 없거나 하한이 있어 판단불가가 될 수 있는 정책 (참고용)
WITH RECURSIVE ages (a) AS (SELECT 0 UNION ALL SELECT a + 1 FROM ages WHERE a < 120)
SELECT
    (SELECT COUNT(*) FROM policy p
      WHERE p.active = TRUE
        AND NOT EXISTS (
            SELECT 1 FROM ages
            WHERE ( (p.min_age IS NULL OR p.min_age = 0 OR p.min_age <= a)
                    AND (p.max_age IS NULL OR p.max_age = 0 OR a <= p.max_age) )
               OR (p.max_age > 0 AND p.min_age > p.max_age)))                    AS age_never_match,
    (SELECT COUNT(*) FROM policy
      WHERE active = TRUE AND (earn_min_amt >= 100000 OR earn_max_amt >= 100000)) AS amount_in_won,
    (SELECT COUNT(*) FROM policy
      WHERE active = TRUE AND earn_cnd_se_cd = '0043002'
        AND (COALESCE(earn_max_amt, 0) = 0 OR COALESCE(earn_min_amt, 0) > 0))  AS cmp_unknown;

-- [9] 분류 (D-018). uncategorized: 묶음표에 맞는 칩이 없어 결과에 나오지 않는 정책 (새 분류가 생겼다는 신호)
--     visible: 칩을 하나라도 가진 정책 = 매칭 결과에 나올 수 있는 정책 (D-021 부터 모든 탭이 열려 있다)
SELECT SUM(IF(NOT EXISTS (SELECT 1 FROM policy_category pc WHERE pc.policy_id = p.id), 1, 0)) AS uncategorized,
       SUM(IF(EXISTS (SELECT 1 FROM policy_category pc WHERE pc.policy_id = p.id), 1, 0))     AS visible,
       COUNT(*)                                                                            AS active
FROM policy p
WHERE p.active = TRUE;

-- [10] 취업 상태 (D-021). 셋 다 0 이어야 매칭 규칙의 전제가 지켜진다.
--      no_code        : 코드가 없는 정책. 취업 상태를 고르면 결과에서 빠진다
--      unlimited_mixed: 제한없음(0013010)과 다른 코드가 함께 붙은 정책. 생기면 규칙을 다시 본다
--      unknown_code   : 공식 코드표(0013001~0013010)에 없는 코드
SELECT
    (SELECT COUNT(*) FROM policy p
      WHERE p.active = TRUE
        AND NOT EXISTS (SELECT 1 FROM policy_job pj WHERE pj.policy_id = p.id))            AS no_code,
    (SELECT COUNT(DISTINCT a.policy_id) FROM policy_job a
       JOIN policy_job b ON b.policy_id = a.policy_id AND b.job_code <> '0013010'
       JOIN policy p ON p.id = a.policy_id AND p.active = TRUE
      WHERE a.job_code = '0013010')                                                        AS unlimited_mixed,
    (SELECT COUNT(*) FROM policy_job pj JOIN policy p ON p.id = pj.policy_id AND p.active = TRUE
      WHERE pj.job_code NOT BETWEEN '0013001' AND '0013010')                               AS unknown_code;
