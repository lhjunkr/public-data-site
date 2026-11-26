-- 12/10 K-Startup 정규화 전 측정 (로컬 DB). 결과는 samples/kstartup-overlap.tsv (리포 밖, .gitignore)
-- 1) 로컬 온통청년 데이터가 언제 것인지
SELECT 'youth_last_success' AS k, DATE_FORMAT(MAX(started_at), '%Y-%m-%d %H:%i') AS v FROM collect_run WHERE source = 'youth' AND status = 'SUCCESS';
SELECT 'youth_active' AS k, COUNT(*) AS v FROM policy WHERE source = 'youth' AND active = TRUE;
-- 2) 온통청년 창업 칩 정책 수
SELECT 'youth_startup_chip' AS k, COUNT(*) AS v FROM policy p JOIN policy_category pc ON pc.policy_id = p.id WHERE p.source = 'youth' AND p.active = TRUE AND pc.category_code = 'jobs.startup';
-- 3) 원본 어디에든 K-Startup 주소가 들어 있는 활성 온통청년 정책 (같은 식별자 = pbancSn 후보)
SELECT 'youth_mentions_kstartup' AS k, COUNT(*) AS v FROM policy_raw r JOIN policy p ON p.source = r.source AND p.policy_no = r.policy_no WHERE r.source = 'youth' AND p.active = TRUE AND CAST(r.raw_json AS CHAR) LIKE '%k-startup.go.kr%';
SELECT 'mention' AS k, p.policy_no, p.title, JSON_UNQUOTE(r.raw_json->'$.aplyUrlAddr') AS apply_url, JSON_UNQUOTE(r.raw_json->'$.refUrlAddr1') AS ref1, JSON_UNQUOTE(r.raw_json->'$.refUrlAddr2') AS ref2 FROM policy_raw r JOIN policy p ON p.source = r.source AND p.policy_no = r.policy_no WHERE r.source = 'youth' AND p.active = TRUE AND CAST(r.raw_json AS CHAR) LIKE '%k-startup.go.kr%';
-- 4) 제목이 글자 그대로 같은 쌍 (참고용, 이것으로 합치지는 않음)
SELECT 'title_exact' AS k, COUNT(*) AS v FROM policy p JOIN policy_raw r ON r.source = 'kstartup' AND TRIM(JSON_UNQUOTE(r.raw_json->'$.biz_pbanc_nm')) = TRIM(p.title) WHERE p.source = 'youth' AND p.active = TRUE;
-- 5) 오늘 K-Startup 공고 번호 (11/25 표본과 비교해 하루 이탈 수를 잰다)
SELECT 'kstartup_id' AS k, policy_no FROM policy_raw WHERE source = 'kstartup' ORDER BY policy_no;
