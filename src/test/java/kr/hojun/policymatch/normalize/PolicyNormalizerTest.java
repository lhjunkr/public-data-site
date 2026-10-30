package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-014 선정 규칙 테스트. 시험용 원본을 policy_raw 에 넣고 정규화를 실제로 돌린다.
 *
 * - @Transactional: 테스트가 끝나면 전부 롤백된다. 로컬 DB 의 실제 데이터는 바뀌지 않는다.
 * - 수집 시각은 2099년으로 넣는다. 실제 데이터보다 항상 뒤이므로 LATEST 가 "가장 최근 수집"이 되고,
 *   실제 원본은 선정 대상에서 빠진다(그래서 로컬 DB 에 데이터가 있어도 결과가 흔들리지 않는다).
 */
@SpringBootTest
@Transactional
class PolicyNormalizerTest {

    private static final String LATEST = "2099-01-02 00:00:00";   // 가장 최근 수집
    private static final String OLDER = "2099-01-01 00:00:00";    // 그 전 수집 = 이번 수집에서 안 보인 정책
    private static final String APPROVED = "0044002";
    private static final String NOT_APPROVED = "0044001";         // 승인(0044002)이 아닌 코드
    private static final String HOUSING = "주택 및 거주지";

    @Autowired JdbcTemplate jdbc;
    @Autowired PolicyNormalizer normalizer;

    @BeforeEach
    void setUp() {
        //   정책번호            승인 코드      중분류                           수집 시각  신청기간
        raw("T-N-IN",          APPROVED,     HOUSING,                         LATEST,   null);
        raw("T-N-MULTI",       APPROVED,     "취업,전월세 및 주거급여 지원",      LATEST,   null);
        raw("T-N-EXPIRED",     APPROVED,     HOUSING,                         LATEST,   "20250101 ~ 20250131");
        raw("T-N-UNAPPROVED",  NOT_APPROVED, HOUSING,                         LATEST,   null);
        raw("T-N-OTHER-CAT",   APPROVED,     "취업",                           LATEST,   null);
        raw("T-N-GONE",        APPROVED,     HOUSING,                         OLDER,    null);
    }

    // ---------- 선정 ----------

    @Test
    void 규칙에_맞는_정책만_적재하고_활성으로_둔다() {
        normalizer.normalize();

        assertTrue(active("T-N-IN"));
        assertTrue(active("T-N-MULTI"), "중분류가 콤마 다중값이어도 대상 분류가 있으면 선정");
        assertFalse(exists("T-N-UNAPPROVED"), "승인되지 않은 정책은 적재하지 않는다");
        assertFalse(exists("T-N-OTHER-CAT"), "대상 분류가 아닌 정책은 적재하지 않는다");
        assertFalse(exists("T-N-GONE"), "가장 최근 수집에 없던 정책은 적재하지 않는다");
    }

    @Test
    void 마감된_정책도_적재한다_마감은_매칭에서_거른다() {
        normalizer.normalize();

        assertTrue(active("T-N-EXPIRED"));
    }

    // ---------- 비활성 ----------

    @Test
    void 규칙에서_빠지면_지우지_않고_비활성으로_바꾼다() {
        normalizer.normalize();
        long id = idOf("T-N-IN");

        // API 에서 사라짐: 이번 수집에서 보이지 않아 fetched_at 이 옛 값에 머문다
        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE policy_no = 'T-N-IN'", OLDER);
        // 승인 취소
        jdbc.update("UPDATE policy_raw SET raw_json = JSON_SET(raw_json, '$.plcyAprvSttsCd', ?) "
                + "WHERE policy_no = 'T-N-MULTI'", NOT_APPROVED);

        NormalizeResult result = normalizer.normalize();

        assertFalse(active("T-N-IN"));
        assertFalse(active("T-N-MULTI"));
        assertEquals(id, idOf("T-N-IN"), "행은 지워지지 않고 id 도 그대로다");
        assertEquals(2, result.deactivated());
    }

    @Test
    void 다시_규칙에_들면_같은_id로_활성으로_돌아온다() {
        normalizer.normalize();
        long id = idOf("T-N-IN");

        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE policy_no = 'T-N-IN'", OLDER);
        normalizer.normalize();
        assertFalse(active("T-N-IN"));

        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE policy_no = 'T-N-IN'", LATEST);
        normalizer.normalize();

        assertTrue(active("T-N-IN"));
        assertEquals(id, idOf("T-N-IN"));
    }

    // ---------- 멱등성 · 안전장치 ----------

    @Test
    void 두_번_실행해도_결과가_같다() {
        normalizer.normalize();
        List<Map<String, Object>> first = snapshot();

        NormalizeResult second = normalizer.normalize();

        assertEquals(first, snapshot(), "값뿐 아니라 updated_at 도 그대로여야 한다(바뀐 행이 없음)");
        assertEquals(0, second.deactivated());
    }

    @Test
    void 선정이_0건이면_비활성_처리하지_않고_멈춘다() {
        normalizer.normalize();
        jdbc.update("DELETE FROM policy_raw");   // 수집이 비었거나 깨진 상황

        assertThrows(IllegalStateException.class, normalizer::normalize);
        assertTrue(active("T-N-IN"), "멈췄으므로 기존 활성 정책은 그대로");
    }

    // ---------- 도우미 ----------

    private void raw(String no, String status, String mediumCategory, String fetchedAt, String period) {
        String json = """
                {"plcyNo":"%s","plcyNm":"%s","plcyAprvSttsCd":"%s","mclsfNm":"%s",
                 "sprtTrgtAgeLmtYn":"Y","sprtTrgtMinAge":"0","sprtTrgtMaxAge":"0",
                 "earnCndSeCd":"0043001","zipCd":"99001,99002","jobCd":"0013010"%s}
                """.formatted(no, no, status, mediumCategory,
                period == null ? "" : ",\"aplyYmd\":\"" + period + "\"");
        jdbc.update("INSERT INTO policy_raw (policy_no, raw_json, fetched_at) VALUES (?, ?, ?)",
                no, json, fetchedAt);
    }

    private boolean active(String no) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT active FROM policy WHERE policy_no = ?", Boolean.class, no));
    }

    private boolean exists(String no) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM policy WHERE policy_no = ?)", Boolean.class, no));
    }

    private long idOf(String no) {
        return jdbc.queryForObject("SELECT id FROM policy WHERE policy_no = ?", Long.class, no);
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("""
                SELECT p.policy_no, p.id, p.active, p.updated_at,
                       (SELECT COUNT(*) FROM policy_region pr WHERE pr.policy_id = p.id) AS regions,
                       (SELECT COUNT(*) FROM policy_job pj WHERE pj.policy_id = p.id) AS jobs
                FROM policy p
                WHERE p.policy_no LIKE 'T-N-%'
                ORDER BY p.policy_no
                """);
    }
}
