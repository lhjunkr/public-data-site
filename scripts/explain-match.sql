-- 매칭 SQL 실행 계획 · 실제 시간 (11/18 · D-009 전제 확인)
-- 앱 PolicyMatchRepository.MATCH_SQL 과 같은 SQL 이다. 이름 붙은 파라미터(:age 등)를 MySQL 변수(@age 등)로 바꿨다.
--   조건: 25세 · 서울 · 소득 없음 · 취업 상태 없음 (scripts/match-25-seoul.json 과 같음)
--   :openChips = application.yml 에서 open: true 인 탭의 칩 12개
--   MATCH_SQL 이나 묶음표를 바꾸면 이 파일도 같이 바꾼다.
-- EXPLAIN ANALYZE 는 쿼리를 실제로 실행하고 단계별 실제 시간을 보여 준다(결과 행은 보내지 않는다).
-- 결과 행까지 받는 시간을 재려면 "EXPLAIN ANALYZE" 줄을 지우고 실행한다: sed '/^EXPLAIN ANALYZE$/d'
SET @age = 25, @sido = '11', @income = NULL, @job = NULL;

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
        SELECT 1 FROM policy_sido ps
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
