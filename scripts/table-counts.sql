-- 표별 정확한 행 수 (D-026 복구 연습 · 11/29 운영 점검)
-- 운영 DB와 복원 DB에서 똑같이 돌려 결과를 diff 한다
-- information_schema 의 TABLE_ROWS 는 InnoDB 에서 추정값이라 COUNT(*) 를 쓴다
-- _tables = 표 개수. 표가 늘면 아래에도 한 줄 추가한다
SELECT '_tables' AS tbl, COUNT(*) AS cnt FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
UNION ALL SELECT 'collect_run', COUNT(*) FROM collect_run
UNION ALL SELECT 'feedback', COUNT(*) FROM feedback
UNION ALL SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history
UNION ALL SELECT 'policy', COUNT(*) FROM policy
UNION ALL SELECT 'policy_category', COUNT(*) FROM policy_category
UNION ALL SELECT 'policy_job', COUNT(*) FROM policy_job
UNION ALL SELECT 'policy_raw', COUNT(*) FROM policy_raw
UNION ALL SELECT 'policy_region', COUNT(*) FROM policy_region
UNION ALL SELECT 'region', COUNT(*) FROM region
ORDER BY tbl;
