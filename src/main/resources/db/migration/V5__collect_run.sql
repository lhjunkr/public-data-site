-- D-017: 수집 실행마다 결과를 남긴다. 정규화는 마지막으로 "성공한" 수집을 기준으로 정책을 고른다.
-- 10/30 사고: 수집이 9페이지에서 실패해 앞 800건만 갱신됐는데, "가장 최근 수집" 기준 정규화가
-- 나머지 정상 정책을 이번 수집에서 안 보였다는 이유로 비활성했다.

CREATE TABLE collect_run (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    started_at  DATETIME     NOT NULL COMMENT '수집 시작 시각. 이번 수집에서 저장한 policy_raw.fetched_at 과 같은 값',
    finished_at DATETIME     NULL,
    status      VARCHAR(10)  NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED',
    tot_count   INT          NULL     COMMENT 'API 가 알려준 전체 건수',
    items_seen  INT          NULL     COMMENT '실제로 받은 건수',
    message     VARCHAR(500) NULL     COMMENT '실패 사유',
    PRIMARY KEY (id),
    KEY idx_collect_run_status_started (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
