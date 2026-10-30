-- 10/14 판단 재료: 나이 표시값(age_limit_yn)의 실제 의미와 소득 금액의 단위를 원문과 대조한다.
-- 읽기만 한다(SELECT). 실행 방법은 quality-check.sql 과 같다.
--
-- "본문" = 정책명 + 설명 + 지원내용 + 추가자격 + 기타. 본문에 "NN세"가 적혀 있으면 나이 조건이 실제로 있다는 신호다.

-- [A] 나이 그룹별: 본문에 나이 표기가 있는 비율, 최대 나이 숫자가 본문에 그대로 있는 비율
SELECT grp,
       COUNT(*)                                             AS n,
       SUM(IF(body REGEXP '[0-9]{2} ?세', 1, 0))            AS text_has_age,
       SUM(IF(max_age > 0 AND (LOCATE(CONCAT(max_age, '세'), body) > 0
                            OR LOCATE(CONCAT(max_age, ' 세'), body) > 0), 1, 0)) AS max_in_text
FROM (
    SELECT CASE
             WHEN min_age IS NULL OR max_age IS NULL THEN CONCAT(age_limit_yn, ' 값 없음')
             WHEN min_age = 0 AND max_age = 0       THEN CONCAT(age_limit_yn, ' 0/0')
             WHEN min_age > max_age                 THEN CONCAT(age_limit_yn, ' 최소>최대')
             ELSE                                        CONCAT(age_limit_yn, ' 범위')
           END AS grp,
           max_age,
           CONCAT_WS(' ', title, description, support_content, extra_qualification, etc_matters) AS body
    FROM policy
    WHERE active = TRUE
) t
GROUP BY grp
ORDER BY grp;

-- [B] Y + 범위 표본 10건 — "Y = 제한 없음"이라면 본문에 나이 조건이 없어야 한다
SELECT policy_no, LEFT(title, 25) AS title, min_age, max_age,
       REGEXP_SUBSTR(CONCAT_WS(' ', description, support_content, extra_qualification, etc_matters),
                     '.{0,15}[0-9]{2} ?세.{0,15}') AS age_text
FROM policy
WHERE active = TRUE AND age_limit_yn = 'Y' AND NOT (min_age = 0 AND max_age = 0) AND min_age <= max_age
ORDER BY view_count DESC
LIMIT 10;

-- [C] N + 0/0 전부 (16건 예상) — 지금은 0세가 아니면 결과에 절대 나오지 않는 정책들
SELECT policy_no, LEFT(title, 25) AS title,
       REGEXP_SUBSTR(CONCAT_WS(' ', description, support_content, extra_qualification, etc_matters),
                     '.{0,15}[0-9]{2} ?세.{0,15}') AS age_text
FROM policy
WHERE active = TRUE AND age_limit_yn = 'N' AND min_age = 0 AND max_age = 0
ORDER BY view_count DESC;

-- [D] 최소 > 최대, 값 없음 전부 (7건 예상)
SELECT policy_no, age_limit_yn, LEFT(title, 25) AS title, min_age, max_age,
       REGEXP_SUBSTR(CONCAT_WS(' ', description, support_content, extra_qualification, etc_matters),
                     '.{0,15}[0-9]{2} ?세.{0,15}') AS age_text
FROM policy
WHERE active = TRUE AND (min_age > max_age OR min_age IS NULL OR max_age IS NULL);

-- [E] 소득 금액비교 전부 (40건 예상) — 금액 크기와 원문의 단위 표기를 나란히 본다
SELECT policy_no, LEFT(title, 20) AS title, earn_min_amt, earn_max_amt,
       LEFT(earn_etc_cn, 30) AS earn_etc,
       REGEXP_SUBSTR(CONCAT_WS(' ', earn_etc_cn, extra_qualification, support_content, description),
                     '.{0,12}[0-9,]+ ?(만 ?원|원)[^ ]{0,8}') AS amount_text
FROM policy
WHERE active = TRUE AND earn_cnd_se_cd = '0043002'
ORDER BY earn_max_amt;
