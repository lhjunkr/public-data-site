package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * 마이홈 정규화 (12/11). 실제 MySQL 에 시험 원본을 넣고 정규화 → 매칭까지 확인한 뒤 롤백한다.
 *
 * 시험 원본은 2099년 수집으로 넣어, 로컬 DB 의 실제 마이홈 원본이 섞이지 않게 한다(K-Startup 시험과 같은 방식).
 * 로컬 DB 의 실제 마이홈 정책은 setUp 에서 먼저 비활성으로 둔다(롤백됨). 그대로 두면 정규화가 그것을 전부 끄려다
 * 대량 비활성 상한에 걸린다.
 */
@SpringBootTest
@Transactional
class MyhomeNormalizerTest {

    private static final String AT = "2099-01-02 00:00:00";
    private static final String LATER = "2099-01-03 00:00:00";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MyhomeNormalizer normalizer;
    @Autowired PolicyMatchRepository matchRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE policy SET active = FALSE WHERE source = 'myhome' AND active = TRUE "
                + "AND policy_no NOT LIKE 'rent-T-%' AND policy_no NOT LIKE 'sale-T-%'");
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('myhome', ?, ?, 'SUCCESS')",
                AT, AT);
    }

    @Test
    void 원본_칸을_policy_칸으로_옮기고_지역_행을_시도로_모은다() {
        Map<String, Object> gangnam = row("T-MN-1", "서울특별시", "강남구");
        gangnam.put("pblancNm", "2026년 서울 전세임대 &#40;청년&#41; 입주자 모집");
        gangnam.put("suplyTyNm", "전세임대");
        gangnam.put("houseTyNm", "다가구");
        gangnam.put("sumSuplyCo", 3);
        gangnam.put("beginDe", "20261201");
        gangnam.put("endDe", "20261210");
        Map<String, Object> seocho = copy(gangnam, "서울특별시", "서초구");
        seocho.put("sumSuplyCo", 2);
        Map<String, Object> seongnam = copy(gangnam, "경기도", "성남시");
        seongnam.put("sumSuplyCo", 5);
        insertRaw("rent-T-MN-1-1", List.of(gangnam, seocho, seongnam), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.candidates());
        assertEquals(1, result.policies());
        Map<String, Object> p = policy("rent-T-MN-1-1");
        assertEquals("2026년 서울 전세임대 (청년) 입주자 모집", p.get("title"));
        assertEquals("주거", p.get("category_large"));
        assertEquals("전세임대", p.get("category_medium"), "공급 유형");
        assertNull(p.get("min_age"), "나이 칸이 없다 → 비움(D-029 ③)");
        assertNull(p.get("max_age"));
        assertNull(p.get("earn_cnd_se_cd"), "소득 칸이 없다 → 비움");
        assertEquals("20261201 ~ 20261210", p.get("apply_period_text"));
        assertEquals("2026-12-01", String.valueOf(p.get("apply_start_date")));
        assertEquals("2026-12-10", String.valueOf(p.get("apply_end_date")));
        assertEquals("https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=T-MN-1",
                p.get("apply_url"));
        assertEquals("한국토지주택공사", p.get("supervising_org"));
        assertEquals(MyhomeNormalizer.QUALIFICATION_NOTICE, p.get("extra_qualification"), "확인 필요 뱃지(D-003)");

        String support = (String) p.get("support_content");
        assertTrue(support.contains("공급 유형: 전세임대"), support);
        assertTrue(support.contains("주택 유형: 다가구"), support);
        assertTrue(support.contains("공급 호수: 합계 10호"), support);
        assertTrue(support.contains("서울특별시 강남구 · 3호"), support);
        assertTrue(support.contains("경기도 성남시 · 5호"), support);

        long id = idOf("rent-T-MN-1-1");
        assertEquals(Set.of("11", "41"), links("policy_sido", "sido", id), "지역 행 3개 → 시도 2개");
        assertEquals(Set.of("0013010"), links("policy_job", "job_code", id), "취업 상태 제한없음");
        assertEquals(Set.of("housing.house"), links("policy_category", "category_code", id), "주거 · 주택 칩");
    }

    @Test
    void 분양은_공급_유형이_공공분양이다() {
        // 12/5: 분양 응답에는 공급 유형 칸(suplyTyNm)이 없다
        Map<String, Object> sale = row("T-MN-2", "세종특별자치시", "");
        sale.remove("suplyTyNm");
        insertRaw("sale-T-MN-2-1", List.of(sale), AT);

        normalizer.normalize();

        Map<String, Object> p = policy("sale-T-MN-2-1");
        assertEquals("공공분양", p.get("category_medium"));
        assertTrue(((String) p.get("support_content")).contains("세종특별자치시 전체"), "시군구가 비면 시도 전체 공고");
        assertEquals(Set.of("36"), links("policy_sido", "sido", idOf("sale-T-MN-2-1")));
    }

    @Test
    void 시도_정식_이름을_우리_시도_코드로_바꾼다() {
        // 12/5: 마이홈은 정식 이름으로 온다. 통합 시도(전남광주)와 특별자치도도 매핑 표(sido.regions)에 있다
        insertRaw("rent-T-MN-3-1", List.of(row("T-MN-3", "전남광주통합특별시", "")), AT);
        insertRaw("rent-T-MN-4-1", List.of(row("T-MN-4", "강원특별자치도", "춘천시")), AT);
        insertRaw("rent-T-MN-5-1", List.of(row("T-MN-5", "전북특별자치도", "")), AT);

        normalizer.normalize();

        assertEquals(Set.of("12"), links("policy_sido", "sido", idOf("rent-T-MN-3-1")));
        assertEquals(Set.of("51"), links("policy_sido", "sido", idOf("rent-T-MN-4-1")));
        assertEquals(Set.of("52"), links("policy_sido", "sido", idOf("rent-T-MN-5-1")));
    }

    @Test
    void 이십오세_서울_검색에_나오고_나이_소득_취업_조건으로_빠지지_않는다() {
        // 12/11 완료 기준 "지역 매칭 동작". 매칭 SQL 은 한 줄도 고치지 않았다(D-029 ③)
        insertRaw("rent-T-MN-6-1", List.of(row("T-MN-6", "서울특별시", "강남구")), AT);
        insertRaw("rent-T-MN-7-1", List.of(row("T-MN-7", "부산광역시", "")), AT);
        normalizer.normalize();

        Optional<MatchedPolicy> found = find(matchRepository.match(25, "11", null, null), "rent-T-MN-6-1");
        assertTrue(found.isPresent(), "서울 공고는 서울 검색에 나온다");
        assertEquals("myhome", found.get().source());
        assertEquals(IncomeStatus.UNKNOWN, found.get().incomeStatus());
        assertTrue(found.get().needsCheck(), "자격은 공고문 확인");
        assertTrue(found.get().categories().contains("housing.house"));

        assertTrue(find(matchRepository.match(25, "11", 3000L, "0013003"), "rent-T-MN-6-1").isPresent(),
                "연소득 · 취업 상태를 넣어도 빠지지 않는다");
        assertTrue(find(matchRepository.match(60, "11", null, null), "rent-T-MN-6-1").isPresent(), "나이 조건이 없다");
        assertTrue(find(matchRepository.match(25, "11", null, null), "rent-T-MN-7-1").isEmpty(), "부산 공고는 서울에 안 나온다");
        assertTrue(find(matchRepository.match(25, "26", null, null), "rent-T-MN-7-1").isPresent());
    }

    @Test
    void 정정공고가_가리키는_이전_공고는_싣지_않는다() {
        // D-029 ⑤: 같은 식별자(beforePblancId)가 있을 때만 하나로 본다. 12/5: 정정 81행 중 77행은 이전 공고도 응답에 있었다
        insertRaw("rent-T-MN-OLD-1", List.of(row("T-MN-OLD", "서울특별시", "")), AT);
        Map<String, Object> corrected = row("T-MN-NEW", "서울특별시", "");
        corrected.put("sttusNm", "정정공고");
        corrected.put("beforePblancId", "T-MN-OLD");
        insertRaw("rent-T-MN-NEW-1", List.of(corrected), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.duplicates());
        assertEquals(1, result.policies());
        assertEquals(0, count("SELECT COUNT(*) FROM policy WHERE source = 'myhome' AND policy_no = 'rent-T-MN-OLD-1'"));
        assertEquals(Boolean.TRUE, active("rent-T-MN-NEW-1"));
        assertTrue(((String) policy("rent-T-MN-NEW-1").get("support_content")).contains("공고 상태: 정정공고"));
    }

    @Test
    void 이미_실린_공고에_정정공고가_나오면_이전_공고는_비활성된다() {
        insertRaw("rent-T-MN-8-1", List.of(row("T-MN-8", "서울특별시", "")), AT);
        normalizer.normalize();
        assertEquals(Boolean.TRUE, active("rent-T-MN-8-1"));

        // 다음 날 수집: 정정공고 T-MN-9 가 T-MN-8 을 가리키고, T-MN-8 도 함께 응답에 있다
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('myhome', ?, ?, 'SUCCESS')",
                LATER, LATER);
        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE source = 'myhome' AND policy_no = 'rent-T-MN-8-1'", LATER);
        Map<String, Object> corrected = row("T-MN-9", "서울특별시", "");
        corrected.put("beforePblancId", "T-MN-8");
        insertRaw("rent-T-MN-9-1", List.of(corrected), LATER);
        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.deactivated());
        assertEquals(Boolean.FALSE, active("rent-T-MN-8-1"), "지우지 않고 비활성(D-014)");
        assertEquals(Boolean.TRUE, active("rent-T-MN-9-1"));
    }

    @Test
    void 임대와_분양은_정정공고_번호를_서로_맞추지_않는다() {
        // 번호 체계가 같다는 보장이 없다. 분양 공고의 beforePblancId 가 임대 공고를 지우면 안 된다
        insertRaw("rent-T-MN-10-1", List.of(row("T-MN-10", "서울특별시", "")), AT);
        Map<String, Object> sale = row("T-MN-11", "서울특별시", "");
        sale.put("beforePblancId", "T-MN-10");
        insertRaw("sale-T-MN-11-1", List.of(sale), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(0, result.duplicates());
        assertEquals(Boolean.TRUE, active("rent-T-MN-10-1"));
    }

    @Test
    void 다음_수집에서_빠진_공고는_비활성된다() {
        insertRaw("rent-T-MN-12-1", List.of(row("T-MN-12", "서울특별시", "")), AT);
        insertRaw("rent-T-MN-13-1", List.of(row("T-MN-13", "서울특별시", "")), AT);
        normalizer.normalize();

        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('myhome', ?, ?, 'SUCCESS')",
                LATER, LATER);
        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE source = 'myhome' AND policy_no = 'rent-T-MN-12-1'", LATER);
        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertEquals(1, result.deactivated());
        assertEquals(Boolean.TRUE, active("rent-T-MN-12-1"));
        assertEquals(Boolean.FALSE, active("rent-T-MN-13-1"));
    }

    @Test
    void 다른_출처의_정책은_건드리지_않는다() {
        int othersBefore = count("SELECT COUNT(*) FROM policy WHERE source <> 'myhome' AND active = TRUE");
        insertRaw("rent-T-MN-14-1", List.of(row("T-MN-14", "서울특별시", "")), AT);

        normalizer.normalize();

        assertEquals(othersBefore, count("SELECT COUNT(*) FROM policy WHERE source <> 'myhome' AND active = TRUE"));
    }

    @Test
    void 모르는_시도는_연결_없이_싣고_검색에는_안_나온다() {
        insertRaw("rent-T-MN-15-1", List.of(row("T-MN-15", "없는특별시", "")), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertTrue(links("policy_sido", "sido", idOf("rent-T-MN-15-1")).isEmpty());
    }

    @Test
    void 선정이_0건이면_비활성하지_않고_멈춘다() {
        assertThrows(IllegalStateException.class, () -> normalizer.normalize());
    }

    @Test
    void 마감일을_읽고_이천구십구년_이후는_상시로_본다() {
        assertEquals(LocalDate.of(2026, 12, 10), MyhomeNormalizer.date("20261210"));
        assertNull(MyhomeNormalizer.date("99991231"));
        assertNull(MyhomeNormalizer.date("2026121"), "자리 수가 틀리면 읽지 않는다");

        Map<String, Object> open = row("T-MN-16", "서울특별시", "");
        open.put("endDe", "99991231");
        insertRaw("rent-T-MN-16-1", List.of(open), AT);
        normalizer.normalize();
        Map<String, Object> p = policy("rent-T-MN-16-1");
        assertNull(p.get("apply_end_date"));
        assertTrue(String.valueOf(p.get("apply_period_text")).endsWith("~ 상시"));
    }

    @Test
    void 지원_내용에_신청자_개인정보나_금액을_싣지_않는다() {
        // 임대료 단위(원 · 천원)는 12/12 에 실제 공고와 맞춰 본 뒤 싣는다
        Map<String, Object> r = row("T-MN-17", "서울특별시", "");
        r.put("rentGtn", 12345);
        r.put("mtRntchrg", 678);
        insertRaw("rent-T-MN-17-1", List.of(r), AT);

        normalizer.normalize();

        String support = (String) policy("rent-T-MN-17-1").get("support_content");
        assertFalse(support.contains("12345"), support);
        assertFalse(support.contains("678"), support);
    }

    // ---------- 도우미 ----------

    /** 실제 응답에서 쓰는 칸만 채운 지역 행 하나. 모집은 어제 시작, 30일 뒤 마감 */
    private static Map<String, Object> row(String pblancId, String brtc, String signgu) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pblancId", pblancId);
        r.put("houseSn", 1);
        r.put("pblancNm", "시험 모집공고 " + pblancId);
        r.put("sttusNm", "공고중");
        r.put("suplyInsttNm", "한국토지주택공사");
        r.put("suplyTyNm", "국민임대");
        r.put("houseTyNm", "아파트");
        r.put("brtcNm", brtc);
        r.put("signguNm", signgu);
        r.put("beginDe", LocalDate.now().minusDays(1).format(YYYYMMDD));
        r.put("endDe", LocalDate.now().plusDays(30).format(YYYYMMDD));
        r.put("url", "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=" + pblancId);
        return r;
    }

    private static Map<String, Object> copy(Map<String, Object> base, String brtc, String signgu) {
        Map<String, Object> r = new LinkedHashMap<>(base);
        r.put("brtcNm", brtc);
        r.put("signguNm", signgu);
        return r;
    }

    private void insertRaw(String policyNo, List<Map<String, Object>> rows, String fetchedAt) {
        jdbc.update("INSERT INTO policy_raw (source, policy_no, raw_json, fetched_at) VALUES ('myhome', ?, ?, ?)",
                policyNo, objectMapper.writeValueAsString(new ArrayList<>(rows)), fetchedAt);
    }

    private Map<String, Object> policy(String no) {
        return jdbc.queryForMap("SELECT * FROM policy WHERE source = 'myhome' AND policy_no = ?", no);
    }

    private long idOf(String no) {
        return jdbc.queryForObject("SELECT id FROM policy WHERE source = 'myhome' AND policy_no = ?", Long.class, no);
    }

    private Boolean active(String no) {
        return jdbc.queryForObject("SELECT active FROM policy WHERE source = 'myhome' AND policy_no = ?",
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
