-- D-018: 정책마다 화면 분류(탭.칩 코드, 예: housing.rent)를 연결한다.
-- 원본 중분류(mclsfNm)에 콤마 다중값이 있으므로 ADR-001 과 같은 이유로 연결 표로 둔다.
-- 원본 이름은 policy.category_medium 에 그대로 남아 있다. 이 표에는 묶음표(application.yml)로 바꾼 코드만 둔다.

CREATE TABLE policy_category (
    policy_id     BIGINT      NOT NULL,
    category_code VARCHAR(40) NOT NULL COMMENT '탭.칩 코드. application.yml 의 category.tabs 참고',
    PRIMARY KEY (policy_id, category_code),
    KEY idx_policy_category_code (category_code),
    FOREIGN KEY (policy_id) REFERENCES policy(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
