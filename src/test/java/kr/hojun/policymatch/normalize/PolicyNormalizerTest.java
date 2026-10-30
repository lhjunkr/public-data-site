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
 * - 수집 시각은 2099년으로 넣는다. 실제 데이터보다 항상 뒤이므로 LATEST 가 "마지막 성공 수집"이 되고,
 *   실제 원본은 선정 대상에서 빠진다(그래서 로컬 DB 에 데이터가 있어도 결과가 흔들리지 않는다).
 * - 선정 기준은 collect_run 의 마지막 SUCCESS 다(D-017). setUp 에서 LATEST 에 성공한 수집 기록을 넣는다.
 */
@SpringBootTest
@Transactional
class PolicyNormalizerTest {

    private static final String LATEST = "2099-01-02 00:00:00";   // 마지막 성공 수집
    private static final String OLDER = "2099-01-01 00:00:00";    // 그 전 수집 = 이후로 안 보인 정책
    private static final String FAILED_AT = "2099-01-03 00:00:00"; // 성공 수집 뒤의 실패한 수집
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
        run(LATEST, "SUCCESS");
    }

    // ---------- 선정 ----------

    @Test
    void 승인된_정책은_분류와_상관없이_적재하고_활성으로_둔다() {
        // D-018: 예전(D-014)에는 중분류 3개만 적재했다. 이제 분류는 화면에서 묶음표로 나눈다
        normalizer.normalize();

        assertTrue(active("T-N-IN"));
        assertTrue(active("T-N-MULTI"));
        assertTrue(active("T-N-OTHER-CAT"), "취업처럼 닫힌 탭의 분류여도 적재한다");
        assertFalse(exists("T-N-UNAPPROVED"), "승인되지 않은 정책은 적재하지 않는다");
        assertFalse(exists("T-N-GONE"), "마지막 성공 수집 이후로 안 보인 정책은 적재하지 않는다");
    }

    // ---------- 분류 (D-018) ----------

    @Test
    void 중분류를_묶음표의_칩으로_연결한다() {
        normalizer.normalize();

        assertEquals(List.of("housing.house"), chips("T-N-IN"));
        assertEquals(List.of("housing.rent", "jobs.employment"), chips("T-N-MULTI"), "콤마 다중값은 칩 여러 개");
        assertEquals(List.of("jobs.employment"), chips("T-N-OTHER-CAT"));
    }

    @Test
    void 묶음표에_없는_중분류만_가진_정책은_분류_없이_적재한다() {
        raw("T-N-UNKNOWN", APPROVED, "새로운분류", LATEST, null);

        NormalizeResult result = normalizer.normalize();

        assertTrue(active("T-N-UNKNOWN"), "데이터는 남겨 둔다 (묶음표에 넣으면 다음 정규화부터 보인다)");
        assertTrue(chips("T-N-UNKNOWN").isEmpty());
        assertEquals(1, result.uncategorized());
    }

    @Test
    void 원_단위_소득_금액은_만원으로_바꿔_저장한다() {
        // 10/30 측정: 상한 5,000(만원)과 43,056,240(원)이 섞여 있었다 (D-016)
        jdbc.update("UPDATE policy_raw SET raw_json = JSON_SET(raw_json, '$.earnCndSeCd', '0043002', "
                + "'$.earnMinAmt', '0', '$.earnMaxAmt', '43056240') WHERE policy_no = 'T-N-IN'");
        jdbc.update("UPDATE policy_raw SET raw_json = JSON_SET(raw_json, '$.earnCndSeCd', '0043002', "
                + "'$.earnMaxAmt', '5000') WHERE policy_no = 'T-N-MULTI'");

        normalizer.normalize();

        long won = jdbc.queryForObject("SELECT earn_max_amt FROM policy WHERE policy_no = 'T-N-IN'", Long.class);
        long manwon = jdbc.queryForObject("SELECT earn_max_amt FROM policy WHERE policy_no = 'T-N-MULTI'", Long.class);
        assertEquals(4306L, won, "원 단위 → 만원 (올림)");
        assertEquals(5000L, manwon, "만원 단위는 그대로");
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

    // ---------- 수집 기록 기준 (D-017) ----------

    @Test
    void 실패한_수집이_있어도_마지막_성공_수집_기준으로_고른다() {
        // 10/30 사고 재현: 성공 수집 뒤에 앞부분만 갱신하고 실패한 수집이 있다.
        // 예전 기준(가장 최근 fetched_at)이었다면 T-N-IN 만 선정되고 나머지는 모두 비활성됐다.
        normalizer.normalize();
        run(FAILED_AT, "FAILED");
        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE policy_no = 'T-N-IN'", FAILED_AT);

        NormalizeResult result = normalizer.normalize();

        assertTrue(active("T-N-IN"), "실패한 수집에서 본 정책도 존재가 확인된 것");
        assertTrue(active("T-N-MULTI"), "실패한 수집에서 못 봤어도 성공 수집에서 본 정책");
        assertTrue(active("T-N-EXPIRED"));
        assertEquals(0, result.deactivated());
    }

    @Test
    void 성공한_수집이_없으면_정규화하지_않는다() {
        normalizer.normalize();
        jdbc.update("DELETE FROM collect_run");

        assertThrows(IllegalStateException.class, normalizer::normalize);
        assertTrue(active("T-N-IN"), "멈췄으므로 기존 활성 정책은 그대로");
    }

    // ---------- 실패 격리 (D-019) ----------

    @Test
    void 값이_칸보다_긴_정책_하나는_건너뛰고_나머지는_정규화한다() {
        // 10/30 사고: ref_url 하나가 칸보다 길어 3,763건 전체 정규화가 멈췄다
        rawWithLongUrl("T-N-LONG");

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.failed());
        assertFalse(exists("T-N-LONG"), "넣지 못한 정책은 남지 않는다");
        assertTrue(active("T-N-IN"), "나머지는 정상");
        assertTrue(active("T-N-MULTI"));
    }

    @Test
    void 실패가_허용치를_넘으면_전체를_되돌린다() {
        // 많이 실패하면 개별 데이터가 아니라 구조 문제다(칸 크기, 설정 등)
        normalizer.normalize();
        for (int i = 0; i <= PolicyNormalizer.MIN_FAILURE_LIMIT; i++) {
            rawWithLongUrl("T-N-LONG-" + i);
        }

        assertThrows(IllegalStateException.class, normalizer::normalize);
        assertTrue(active("T-N-IN"), "되돌리므로 기존 활성 정책은 그대로");
    }

    // ---------- 도우미 ----------

    /** ref_url 칸(2,048자)보다 긴 참고 링크를 가진 원본 */
    private void rawWithLongUrl(String no) {
        raw(no, APPROVED, HOUSING, LATEST, null);
        jdbc.update("UPDATE policy_raw SET raw_json = JSON_SET(raw_json, '$.refUrlAddr1', ?) WHERE policy_no = ?",
                "https://example.com/" + "a".repeat(3000), no);
    }

    private void run(String startedAt, String status) {
        jdbc.update("INSERT INTO collect_run (started_at, finished_at, status) VALUES (?, ?, ?)",
                startedAt, startedAt, status);
    }

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

    private List<String> chips(String no) {
        return jdbc.queryForList("""
                SELECT pc.category_code FROM policy_category pc JOIN policy p ON p.id = pc.policy_id
                WHERE p.policy_no = ? ORDER BY pc.category_code
                """, String.class, no);
    }

    private long idOf(String no) {
        return jdbc.queryForObject("SELECT id FROM policy WHERE policy_no = ?", Long.class, no);
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("""
                SELECT p.policy_no, p.id, p.active, p.updated_at,
                       (SELECT COUNT(*) FROM policy_region pr WHERE pr.policy_id = p.id) AS regions,
                       (SELECT COUNT(*) FROM policy_job pj WHERE pj.policy_id = p.id) AS jobs,
                       (SELECT COUNT(*) FROM policy_category pc WHERE pc.policy_id = p.id) AS categories
                FROM policy p
                WHERE p.policy_no LIKE 'T-N-%'
                ORDER BY p.policy_no
                """);
    }
}
