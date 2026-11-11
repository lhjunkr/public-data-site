-- D-027 · 11/21 실험(docs/performance.md 개선 1 실험): 매칭은 지역을 시도 단위로만 보므로 정책별 시도를 따로 저장한다.
-- policy_region(시군구) 약 21만 행 → policy_sido 약 1만 5천 행. 매칭 SQL 은 이 표만 본다.
-- 시도 = 지역 코드 앞 두 자리. region.sido 와 같은 규칙이다(PolicyNormalizer.sidoOf).
-- 확장 → 전환 → 축소: 이번에는 표를 만들고 채우기만 한다. policy_region 은 롤백을 위해 남겨 두고 나중에 지운다.

CREATE TABLE policy_sido (
    policy_id BIGINT  NOT NULL,
    sido      CHAR(2) NOT NULL COMMENT '시도 코드 2자리. 지역 코드 앞 두 자리',
    PRIMARY KEY (policy_id, sido),
    FOREIGN KEY (policy_id) REFERENCES policy(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 배포하자마자 매칭 결과가 비지 않도록 지금의 policy_region 으로 채운다(백필). 이후로는 정규화가 채운다.
-- 서울 정책 하나에 구 코드가 25개면 앞 두 자리 '11' 이 25번 나오므로 DISTINCT 로 한 번씩만 넣는다(기본 키 중복 방지).
INSERT INTO policy_sido (policy_id, sido)
SELECT DISTINCT policy_id, LEFT(region_code, 2) FROM policy_region;
