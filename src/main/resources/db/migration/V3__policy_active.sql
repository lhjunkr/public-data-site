-- D-014: 규칙에서 빠진 정책은 지우지 않고 비활성으로 표시한다.
-- 지우면 연결 표(policy_region, policy_job)를 먼저 지워야 하고, 다시 나타날 때 id 가 바뀐다.
-- 기존 행은 TRUE 로 시작하고, 다음 정규화에서 규칙에 따라 다시 정해진다.

ALTER TABLE policy
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'D-014: 서비스 대상 여부. 규칙에서 빠지면 FALSE' AFTER view_count;
