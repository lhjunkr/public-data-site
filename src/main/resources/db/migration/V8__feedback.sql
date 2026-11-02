-- D-024: 사이트 안 "의견 보내기". 의견 본문은 필수, 답장용 이메일은 선택(동의한 경우만).
-- 이메일은 답장 처리(DONE) 때 바로 지우고, 처리하지 못했더라도 90일이 지나면 매일 새벽 지운다.
-- 접속 IP 는 저장하지 않는다 (도배 방지에만 메모리에서 잠깐 쓴다).

CREATE TABLE feedback (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '받은 시각 (DB 시간대 = 한국 시간)',
    message     VARCHAR(1000) NOT NULL COMMENT '의견 본문 (1~1,000자)',
    email       VARCHAR(254)  NULL     COMMENT '답장용 이메일 (선택 · 동의 시). 답장 처리 또는 90일 뒤 삭제',
    status      VARCHAR(10)   NOT NULL DEFAULT 'NEW' COMMENT 'NEW: 받음 / DONE: 답장 · 처리 끝',
    answered_at DATETIME      NULL     COMMENT 'DONE 으로 바꾼 시각',
    PRIMARY KEY (id),
    KEY idx_feedback_status (status, created_at),
    KEY idx_feedback_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
