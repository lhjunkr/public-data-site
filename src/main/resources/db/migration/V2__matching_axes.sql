-- V1에는 매칭 4축(나이·지역·소득·취업상태) 중 소득조건과 취업상태를 저장할 곳이 없었다.
-- 수집한 원본을 정규화해 넣으려면 아래 구조가 먼저 있어야 한다.

ALTER TABLE policy
    ADD COLUMN earn_cnd_se_cd CHAR(7) NULL
        COMMENT '소득조건: 0043001 무관 / 0043002 금액비교 / 0043003 기타' AFTER max_age,
    ADD COLUMN earn_min_amt   BIGINT  NULL COMMENT '연소득 하한(원)'        AFTER earn_cnd_se_cd,
    ADD COLUMN earn_max_amt   BIGINT  NULL COMMENT '연소득 상한(원)'        AFTER earn_min_amt,
    ADD COLUMN earn_etc_cn    TEXT    NULL COMMENT '소득 기타조건 원문'      AFTER earn_max_amt;

-- jobCd는 콤마 다중값이 실제로 존재하므로 ADR-001에 따라 연결 표로 둔다.
-- 코드의 정식 명칭이 아직 미확정이라 코드 마스터 표는 만들지 않는다.
CREATE TABLE policy_job (
    policy_id BIGINT  NOT NULL,
    job_code  CHAR(7) NOT NULL,
    PRIMARY KEY (policy_id, job_code),
    FOREIGN KEY (policy_id) REFERENCES policy(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 나이는 매칭의 주력 축이다(jobCd 96%, earnCndSeCd 95%가 한 값에 쏠려 변별력이 없음).
CREATE INDEX idx_policy_age ON policy (min_age, max_age);
