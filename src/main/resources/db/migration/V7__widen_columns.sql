-- D-019: 10/30 전체 정규화(3,763건)가 ref_url 때문에 실패했다. 측정(scripts/field-lengths.sql)에서
-- ref_url 최대 542자로 칸(500자)을 넘었고, apply_url 도 최대 408자로 칸에 가까웠다.
-- URL 은 2,048자까지 받는다(측정 최대의 약 4배). 분류 이름은 콤마 다중값이 늘 수 있어 넉넉히 둔다(측정 최대 35자).
-- 모두 VARCHAR 를 유지한다 — JPA 엔티티의 스키마 검증(ddl-auto=validate)은 길이가 아니라 종류를 본다.

ALTER TABLE policy
    MODIFY COLUMN apply_url       VARCHAR(2048) NULL,
    MODIFY COLUMN ref_url         VARCHAR(2048) NULL,
    MODIFY COLUMN category_large  VARCHAR(200)  NULL,
    MODIFY COLUMN category_medium VARCHAR(200)  NULL;
