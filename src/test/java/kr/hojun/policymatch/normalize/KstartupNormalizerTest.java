package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.hojun.policymatch.match.IncomeStatus;
import kr.hojun.policymatch.match.MatchedPolicy;
import kr.hojun.policymatch.match.PolicyMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * K-Startup 정규화 (12/10). 실제 MySQL 에 시험 원본을 넣고 정규화 → 매칭까지 확인한 뒤 롤백한다.
 *
 * 시험 원본은 2099년 수집으로 넣는다. 정규화는 "마지막 성공 수집 이후"만 고르므로, 로컬 DB 의 실제 K-Startup 원본은 섞이지 않는다.
 * 로컬 DB 의 실제 K-Startup 정책은 setUp 에서 먼저 비활성으로 둔다(롤백됨). 그대로 두면 정규화가 그것을 전부 끄려다
 * 대량 비활성 상한에 걸린다(PolicyNormalizerTest 와 같은 이유).
 */
@SpringBootTest
@Transactional
class KstartupNormalizerTest {

    private static final String AT = "2099-01-02 00:00:00";
    private static final String LATER = "2099-01-03 00:00:00";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired KstartupNormalizer normalizer;
    @Autowired PolicyMatchRepository matchRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE policy SET active = FALSE WHERE source = 'kstartup' AND active = TRUE AND policy_no NOT LIKE 'T-%'");
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('kstartup', ?, ?, 'SUCCESS')",
                AT, AT);
    }

    @Test
    void 원본_칸을_policy_칸으로_옮기고_엔티티를_푼다() {
        Map<String, Object> raw = announcement("T-KN-1", "서울", "만 20세 이상 ~ 만 39세 이하");
        raw.put("biz_pbanc_nm", "2027 예비창업패키지 &#40;R&amp;D&#41; 모집");
        raw.put("supt_biz_clsfc", "기술개발(R&amp;D)");
        raw.put("pbanc_rcpt_bgng_dt", "20261201");
        raw.put("pbanc_rcpt_end_dt", "20261231");
        raw.put("aply_trgt_ctnt", "만 19세~39세 예비창업자");
        raw.put("pbanc_ctnt", "첫 줄\r\n둘째 줄");
        raw.put("support_ctnt", "사업화 자금 최대 1억 원");
        raw.put("aply_mthd_eml_rcpt_istc", "secret@example.com");
        raw.put("prch_cnpl_no", "02-000-0000");
        insertRaw(raw, AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.candidates());
        assertEquals(1, result.policies());
        Map<String, Object> p = policy("T-KN-1");
        assertEquals("2027 예비창업패키지 (R&D) 모집", p.get("title"));
        assertEquals("창업", p.get("category_large"));
        assertEquals("기술개발(R&D)", p.get("category_medium"));
        assertEquals(20, ((Number) p.get("min_age")).intValue());
        assertEquals(39, ((Number) p.get("max_age")).intValue());
        assertEquals("20261201 ~ 20261231", p.get("apply_period_text"));
        assertEquals("2026-12-31", String.valueOf(p.get("apply_end_date")));
        assertEquals("https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do?schM=view&pbancSn=T-KN-1",
                p.get("apply_url"));
        assertEquals("https://example.com/guide", p.get("ref_url"));
        assertEquals("창업지원센터", p.get("supervising_org"));
        assertNull(p.get("earn_cnd_se_cd"), "소득 칸은 비운다(D-029 ③)");
        assertEquals("만 19세~39세 예비창업자", p.get("extra_qualification"), "확인 필요 뱃지(D-003)");

        String support = (String) p.get("support_content");
        assertTrue(support.contains("지원 분야: 기술개발(R&D)"), support);
        assertTrue(support.contains("첫 줄\n둘째 줄"), "줄바꿈을 그대로 살린다");
        assertTrue(support.contains("사업화 자금 최대 1억 원"));
        assertFalse(support.contains("secret@example.com"), "담당자 이메일은 싣지 않는다");
        assertFalse(support.contains("02-000-0000"), "담당자 전화는 싣지 않는다");

        long id = ((Number) p.get("id")).longValue();
        assertEquals(Set.of("11"), links("policy_sido", "sido", id));
        assertEquals(Set.of("0013010"), links("policy_job", "job_code", id), "취업 상태 제한없음");
        assertEquals(Set.of("jobs.startup"), links("policy_category", "category_code", id), "일자리 · 창업 칩");
    }

    @Test
    void 전국은_16개_시도로_펼친다() {
        insertRaw(announcement("T-KN-2", "전국", null), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(16, links("policy_sido", "sido", idOf("T-KN-2")).size());
        assertEquals(16, result.regionLinks(), "K-Startup 은 시도 연결 수를 센다");
    }

    @Test
    void 이십오세_서울_검색에_나오고_출처가_붙고_소득은_판단불가다() {
        // 12/10 완료 기준. 매칭 SQL 은 한 줄도 고치지 않았다(D-029 ③)
        insertRaw(announcement("T-KN-3", "전국", "만 20세 이상 ~ 만 39세 이하"), AT);
        insertRaw(announcement("T-KN-4", "부산", null), AT);
        normalizer.normalize();

        Optional<MatchedPolicy> found = find(matchRepository.match(25, "11", null, null), "T-KN-3");
        assertTrue(found.isPresent(), "전국 공고는 서울 검색에 나온다");
        assertEquals("kstartup", found.get().source());
        assertEquals(IncomeStatus.UNKNOWN, found.get().incomeStatus());
        assertTrue(found.get().categories().contains("jobs.startup"));

        assertTrue(find(matchRepository.match(25, "11", 3000L, "0013003"), "T-KN-3").isPresent(),
                "연소득 · 취업 상태를 넣어도 빠지지 않는다(제한없음)");
        assertTrue(find(matchRepository.match(45, "11", null, null), "T-KN-3").isEmpty(), "20~39세 공고는 45세에 안 나온다");
        assertTrue(find(matchRepository.match(25, "11", null, null), "T-KN-4").isEmpty(), "부산 공고는 서울에 안 나온다");
        assertTrue(find(matchRepository.match(25, "26", null, null), "T-KN-4").isPresent());
    }

    @Test
    void 다음_수집에서_빠진_공고는_비활성된다() {
        insertRaw(announcement("T-KN-5", "서울", null), AT);
        insertRaw(announcement("T-KN-6", "서울", null), AT);
        normalizer.normalize();

        // 다음 날 수집: T-KN-5 만 다시 보였다(마감된 T-KN-6 은 응답에 없음)
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('kstartup', ?, ?, 'SUCCESS')",
                LATER, LATER);
        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE source = 'kstartup' AND policy_no = 'T-KN-5'", LATER);
        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertEquals(1, result.deactivated());
        assertEquals(Boolean.TRUE, active("T-KN-5"));
        assertEquals(Boolean.FALSE, active("T-KN-6"), "지우지 않고 비활성(D-014)");
    }

    @Test
    void 온통청년_정책이_같은_공고_번호를_가리키면_싣지_않는다() {
        // D-029 ⑤: 같은 식별자가 있을 때만 하나로 본다. 마감 전 활성 온통청년 정책의 주소에 pbancSn 이 있을 때다
        insertYouthPolicy("T-KN-Y1", "https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do?schM=view&pbancSn=990000001",
                null);
        // 마감이 지난 온통청년 정책은 화면에 안 나오므로, 그 정책 때문에 모집 중인 공고를 숨기지 않는다
        insertYouthPolicy("T-KN-Y2", "https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do?schM=view&pbancSn=990000002",
                LocalDate.now().minusDays(1));
        insertRaw(announcement("990000001", "서울", null), AT);
        insertRaw(announcement("990000002", "서울", null), AT);
        insertRaw(announcement("T-KN-7", "서울", null), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.duplicates());
        assertEquals(2, result.policies());
        assertEquals(0, count("SELECT COUNT(*) FROM policy WHERE source = 'kstartup' AND policy_no = '990000001'"));
        assertEquals(Boolean.TRUE, active("990000002"));
        assertEquals(Boolean.TRUE, active("T-KN-7"));
    }

    @Test
    void 제목만_같은_온통청년_정책은_합치지_않는다() {
        // 잘못 합치면 공고가 사라진다(D-001). 주소에 같은 공고 번호가 있을 때만 합친다
        insertYouthPolicy("T-KN-Y3", "https://www.k-startup.go.kr/main.do", null);
        jdbc.update("UPDATE policy SET title = '같은 제목 공고' WHERE source = 'youth' AND policy_no = 'T-KN-Y3'");
        Map<String, Object> raw = announcement("T-KN-8", "서울", null);
        raw.put("biz_pbanc_nm", "같은 제목 공고");
        insertRaw(raw, AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(0, result.duplicates());
        assertEquals(Boolean.TRUE, active("T-KN-8"));
    }

    @Test
    void 온통청년_정책은_건드리지_않는다() {
        int youthActiveBefore = count("SELECT COUNT(*) FROM policy WHERE source = 'youth' AND active = TRUE");
        insertRaw(announcement("T-KN-9", "서울", null), AT);

        normalizer.normalize();

        assertEquals(youthActiveBefore, count("SELECT COUNT(*) FROM policy WHERE source = 'youth' AND active = TRUE"));
    }

    @Test
    void 모르는_지역은_시도_연결_없이_싣고_검색에는_안_나온다() {
        insertRaw(announcement("T-KN-10", "해외", null), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertTrue(links("policy_sido", "sido", idOf("T-KN-10")).isEmpty());
    }

    @Test
    void 선정이_0건이면_비활성하지_않고_멈춘다() {
        // 성공한 수집 뒤에 받은 원본이 하나도 없다. 그대로 진행하면 활성 공고가 전부 꺼진다
        assertThrows(IllegalStateException.class, () -> normalizer.normalize());
    }

    @Test
    void 공고_번호에서_pbancSn을_꺼낸다() {
        assertEquals(Set.of("179871"), KstartupNormalizer.pbancSnOf(
                "https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do?schM=view&pbancSn=179871"));
        assertEquals(Set.of("176019"), KstartupNormalizer.pbancSnOf(
                "https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do?pbancClssCd=PBC010&amp;schM=view&amp;pbancSn=176019"),
                "11/26 실측: 온통청년 주소에 &amp; 가 그대로 든 경우");
        assertTrue(KstartupNormalizer.pbancSnOf("https://www.k-startup.go.kr/main.do").isEmpty(), "특정 공고가 아님");
        assertTrue(KstartupNormalizer.pbancSnOf("https://other.example.com/?pbancSn=1").isEmpty(), "K-Startup 주소가 아님");
    }

    @Test
    void 이천구십구년_이후_마감은_상시로_본다() {
        assertNull(KstartupNormalizer.date("99991231"));
        assertEquals(LocalDate.of(2026, 12, 31), KstartupNormalizer.date("20261231"));
        assertNull(KstartupNormalizer.date("2026-12-31"), "형식이 다르면 읽지 않는다");
    }

    // ---------- 도우미 ----------

    /** 실제 응답에서 매칭에 쓰는 칸만 채운 공고 한 건. 마감은 30일 뒤 */
    private Map<String, Object> announcement(String no, String region, String age) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("pbanc_sn", no);
        raw.put("biz_pbanc_nm", "시험 공고 " + no);
        raw.put("supt_regin", region);
        raw.put("biz_trgt_age", age);
        raw.put("supt_biz_clsfc", "사업화");
        raw.put("pbanc_rcpt_bgng_dt", LocalDate.now().minusDays(1).format(YYYYMMDD));
        raw.put("pbanc_rcpt_end_dt", LocalDate.now().plusDays(30).format(YYYYMMDD));
        raw.put("detl_pg_url", "https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do?schM=view&pbancSn=" + no);
        raw.put("biz_gdnc_url", "https://example.com/guide");
        raw.put("pbanc_ntrp_nm", "창업지원센터");
        raw.put("sprv_inst", "공공기관");
        raw.put("aply_trgt", "대학생,일반인");
        raw.put("rcrt_prgs_yn", "Y");
        return raw;
    }

    private void insertRaw(Map<String, Object> raw, String fetchedAt) {
        jdbc.update("INSERT INTO policy_raw (source, policy_no, raw_json, fetched_at) VALUES ('kstartup', ?, ?, ?)",
                String.valueOf(raw.get("pbanc_sn")), objectMapper.writeValueAsString(raw), fetchedAt);
    }

    private void insertYouthPolicy(String no, String applyUrl, LocalDate applyEnd) {
        jdbc.update("INSERT INTO policy (source, policy_no, title, apply_url, apply_end_date) VALUES ('youth', ?, ?, ?, ?)",
                no, "온통청년 시험 정책 " + no, applyUrl, applyEnd == null ? null : java.sql.Date.valueOf(applyEnd));
    }

    private Map<String, Object> policy(String no) {
        return jdbc.queryForMap("SELECT * FROM policy WHERE source = 'kstartup' AND policy_no = ?", no);
    }

    private long idOf(String no) {
        return jdbc.queryForObject("SELECT id FROM policy WHERE source = 'kstartup' AND policy_no = ?", Long.class, no);
    }

    private Boolean active(String no) {
        return jdbc.queryForObject("SELECT active FROM policy WHERE source = 'kstartup' AND policy_no = ?",
                Boolean.class, no);
    }

    private Set<String> links(String table, String column, long policyId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE policy_id = ?", String.class, policyId));
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private Optional<MatchedPolicy> find(List<MatchedPolicy> results, String no) {
        long id = idOf(no);
        return results.stream().filter(p -> p.id() == id).findFirst();
    }
}
