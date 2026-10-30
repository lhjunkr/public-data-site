-- 10/30 전체 데이터 측정(docs/quality-check.md)으로 바뀐 해석을 열 설명에 반영한다. 데이터와 구조는 그대로다.
--  - 소득 금액: V2 에 "원"으로 적었지만 대부분 만원 단위였고, 원 단위 값은 정규화 때 만원으로 바꾼다(D-016).
--  - age_limit_yn: 나이 값과 맞지 않는 경우가 많아 매칭에 쓰지 않는다(D-015).

ALTER TABLE policy
    MODIFY COLUMN earn_min_amt BIGINT  NULL COMMENT '연소득 하한(만원). 원 단위 원본은 정규화 때 만원으로 변환(D-016)',
    MODIFY COLUMN earn_max_amt BIGINT  NULL COMMENT '연소득 상한(만원). 원 단위 원본은 정규화 때 만원으로 변환(D-016)',
    MODIFY COLUMN age_limit_yn CHAR(1) NULL COMMENT '원본 나이 제한 표시. 나이 값과 맞지 않아 매칭에 쓰지 않음(D-015)';
