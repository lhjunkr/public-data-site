-- D-029: 새 데이터원(K-Startup · 마이홈 · 잡알리오 · 지방공공기관 채용)을 policy 표에 함께 넣기 위해 출처(source)를 둔다.
-- 소스마다 번호 체계가 달라 정책 번호(policy_no)만으로는 겹칠 수 있으므로 유니크 키를 (source, policy_no)로 바꾼다.
-- collect_run 에도 source 를 둬 "마지막 성공 수집"과 비활성 처리를 소스별로 한다(D-014 · D-017 을 소스별로).
--
-- 기본값 'youth'(온통청년)를 남기는 이유 — 확장 → 전환 → 축소(V9 와 같은 방식)의 "확장" 단계다.
--  - 기존 행은 모두 온통청년이라 기본값으로 채워진다(백필).
--  - 배포 뒤 문제가 생겨 이전 이미지로 되돌려도, source 를 모르는 이전 코드의 INSERT 가 기본값으로 계속 동작한다.
--  - 새 코드는 기본값에 기대지 않고 source 를 항상 직접 넘긴다. 새 소스가 모두 붙고 안정되면(12/16 뒤) 기본값을 지운다(축소).
-- 행 수가 수천 건이라 칸 추가 · 인덱스 교체는 배포 중 몇 초 안에 끝난다.

ALTER TABLE policy_raw
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'youth'
        COMMENT 'D-029: 출처. youth=온통청년 (kstartup · myhome · alio · cleaneye 는 12/9~ 추가)' AFTER id,
    DROP INDEX uk_policy_raw_policy_no,
    ADD UNIQUE KEY uk_policy_raw_source_no (source, policy_no);

ALTER TABLE policy
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'youth'
        COMMENT 'D-029: 출처. policy_no 는 이 출처의 원본 식별자' AFTER id,
    DROP INDEX uk_policy_policy_no,
    ADD UNIQUE KEY uk_policy_source_no (source, policy_no);

ALTER TABLE collect_run
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'youth'
        COMMENT 'D-029: 어느 출처를 수집했는지. 마지막 성공 수집은 출처별로 찾는다' AFTER id,
    DROP INDEX idx_collect_run_status_started,
    ADD KEY idx_collect_run_source_status_started (source, status, started_at);
