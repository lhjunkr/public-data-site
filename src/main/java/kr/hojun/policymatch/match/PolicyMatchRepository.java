package kr.hojun.policymatch.match;

import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 9/25 에 손으로 작성·검증한 매칭 SQL 을 그대로 옮긴 것.
 * MySQL 변수(@age, @sido, @income) 자리가 이름 붙은 파라미터(:age, :sido, :income)로 바뀌었을 뿐이다.
 * 값은 문자열로 이어 붙이지 않고 파라미터로 넘기므로 SQL 인젝션이 불가능하다.
 * p.active = TRUE: 선정 규칙에서 빠진(비활성) 정책은 결과에 내보내지 않는다(D-014).
 *
 * 나이 (D-015) — age_limit_yn 표시값은 보지 않고 나이 값만 본다.
 *  - 최소·최대가 0 또는 NULL 이면 그쪽 경계가 없다. 0/0 = 제한 없음, 18/0 = 18세 이상, 0/39 = 39세 이하.
 *  - 최소 > 최대(둘 다 0 아님)는 값이 뒤집혀 판단할 수 없으므로 결과에 포함한다(D-001).
 *
 * 소득 (D-016) — 금액은 정규화 때 만원 단위로 통일돼 있다. 금액비교(0043002)는 상한을 기준으로 본다.
 *  - 상한이 없으면(0/NULL) 판단불가.
 *  - 상한을 넘으면 미충족.
 *  - 상한 이하인데 하한이 있으면(> 0) 판단불가. 하한 칸에 다른 대상의 기준이 들어간 사례가 있다
 *    (예: 청년 4,000 / 신혼부부 6,000). 상한을 넘는 경우는 어느 해석으로도 미충족이라 그대로 둔다.
 */
@Repository
public class PolicyMatchRepository {

    private static final String MATCH_SQL = """
            SELECT p.id, p.title, p.category_large, p.category_medium,
                   p.support_content, p.apply_period_text, p.apply_url, p.ref_url,
                   p.earn_cnd_se_cd, p.earn_etc_cn, p.extra_qualification,
                   CASE
                     WHEN p.earn_cnd_se_cd = '0043001'                              THEN 'MET'
                     WHEN p.earn_cnd_se_cd IS NULL OR p.earn_cnd_se_cd <> '0043002' THEN 'UNKNOWN'
                     WHEN COALESCE(p.earn_max_amt, 0) = 0                           THEN 'UNKNOWN'
                     WHEN :income IS NULL                                           THEN 'UNKNOWN'
                     WHEN :income > p.earn_max_amt                                  THEN 'NOT_MET'
                     WHEN COALESCE(p.earn_min_amt, 0) > 0                           THEN 'UNKNOWN'
                     ELSE                                                                'MET'
                   END AS income_status
            FROM policy p
            WHERE p.active = TRUE
              AND ( ( (p.min_age IS NULL OR p.min_age = 0 OR p.min_age <= :age)
                      AND (p.max_age IS NULL OR p.max_age = 0 OR :age <= p.max_age) )
                    OR (p.max_age > 0 AND p.min_age > p.max_age) )
              AND ( p.apply_end_date IS NULL OR p.apply_end_date >= CURDATE() )
              AND EXISTS (
                    SELECT 1 FROM policy_region pr
                    JOIN region r ON r.code = pr.region_code
                    WHERE pr.policy_id = p.id AND r.sido = :sido )
            ORDER BY p.view_count DESC, p.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PolicyMatchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MatchedPolicy> match(int age, String sido, Long income) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("age", age)
                .addValue("sido", sido)
                .addValue("income", income);   // null 이면 SQL 에서 :income IS NULL 이 참

        return jdbc.query(MATCH_SQL, params, (rs, rowNum) -> {
            IncomeStatus status = IncomeStatus.valueOf(rs.getString("income_status"));
            String earnEtc = rs.getString("earn_etc_cn");
            String extra = rs.getString("extra_qualification");
            return new MatchedPolicy(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("category_large"),
                    rs.getString("category_medium"),
                    rs.getString("support_content"),
                    rs.getString("apply_period_text"),
                    rs.getString("apply_url"),
                    rs.getString("ref_url"),
                    status,
                    "0043003".equals(rs.getString("earn_cnd_se_cd")) ? earnEtc : null,
                    extra != null && !extra.isBlank());
        });
    }

    /** 존재하지 않는 시도 코드(예: 통합 전 옛 코드 "46")를 조용한 빈 결과 대신 400 으로 돌려보내기 위해 쓴다. */
    public boolean sidoExists(String sido) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM region WHERE sido = :sido)",
                new MapSqlParameterSource("sido", sido), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }
}
