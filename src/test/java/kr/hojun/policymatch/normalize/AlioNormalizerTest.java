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
import kr.hojun.policymatch.category.CategoryCatalog;
import kr.hojun.policymatch.category.CategoryProperties;
import kr.hojun.policymatch.category.CategoryProperties.Chip;
import kr.hojun.policymatch.category.CategoryProperties.Tab;
import kr.hojun.policymatch.match.IncomeStatus;
import kr.hojun.policymatch.match.MatchedPolicy;
import kr.hojun.policymatch.match.PolicyMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 잡알리오 정규화 (12/13). 실제 MySQL 에 시험 원본을 넣고 정규화 → 매칭까지 확인한 뒤 롤백한다.
 *
 * 시험 원본은 2099년 수집으로 넣는다. 정규화는 "마지막 성공 수집 이후"만 고르므로, 로컬 DB 의 실제 잡알리오 원본은 섞이지 않는다.
 * 로컬 DB 의 실제 잡알리오 공고는 setUp 에서 먼저 비활성으로 둔다(롤백됨). 그대로 두면 정규화가 그것을 전부 끄려다
 * 대량 비활성 상한에 걸린다(K-Startup · 마이홈 시험과 같은 이유).
 */
@SpringBootTest
@Transactional
class AlioNormalizerTest {

    private static final String AT = "2099-01-02 00:00:00";
    private static final String LATER = "2099-01-03 00:00:00";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AlioNormalizer normalizer;
    @Autowired PolicyMatchRepository matchRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE policy SET active = FALSE WHERE source = 'alio' AND active = TRUE AND policy_no NOT LIKE 'T-%'");
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('alio', ?, ?, 'SUCCESS')",
                AT, AT);
    }

    @Test
    void 원본_칸을_policy_칸으로_옮긴다() {
        Map<String, Object> raw = posting("990001", "정규직", "신입+경력", "경남,경기");
        raw.put("recrutPbancTtl", "2027년 신입사원(R&amp;D) 채용");
        raw.put("pbancBgngYmd", "20261201");
        raw.put("pbancEndYmd", "20261231");
        raw.put("recrutNope", 3);
        raw.put("acbgCondNmLst", "학력무관");
        raw.put("ncsCdNmLst", "경영.회계.사무,정보통신");
        raw.put("aplyQlfcCn", "만 18세 이상\r\n남자는 병역필");
        raw.put("prefCn", "보훈대상자 가점");
        raw.put("disqlfcRsn", "국가공무원법 결격 사유");
        raw.put("scrnprcdrMthdExpln", "1. 서류전형\r\n문의: secret@example.com, 02-000-0000");
        insertRaw(raw, AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.candidates());
        assertEquals(1, result.policies());
        Map<String, Object> p = policy("990001");
        assertEquals("2027년 신입사원(R&D) 채용", p.get("title"));
        assertEquals("공공기관 채용", p.get("category_large"));
        assertEquals("정규직", p.get("category_medium"));
        assertNull(p.get("min_age"), "나이 칸이 없다(D-029 ③)");
        assertNull(p.get("max_age"));
        assertNull(p.get("earn_cnd_se_cd"), "소득 칸은 비운다(D-029 ③)");
        assertEquals("20261201 ~ 20261231", p.get("apply_period_text"));
        assertEquals("2026-12-01", String.valueOf(p.get("apply_start_date")));
        assertEquals("2026-12-31", String.valueOf(p.get("apply_end_date")));
        assertEquals("https://job.alio.go.kr/recruitview.do?idx=990001", p.get("apply_url"), "신청하기 = 잡알리오 원문");
        assertEquals("https://recruit.example.or.kr/", p.get("ref_url"), "참고 링크 = 기관 채용 페이지");
        assertEquals("시험공단", p.get("supervising_org"));
        assertEquals("만 18세 이상\n남자는 병역필", p.get("extra_qualification"), "확인 필요 뱃지(D-003)");
        assertEquals("보훈대상자 가점", p.get("etc_matters"));

        String support = (String) p.get("support_content");
        assertTrue(support.contains("고용형태: 정규직"), support);
        assertTrue(support.contains("채용 구분: 신입 + 경력"), support);
        assertTrue(support.contains("근무지: 경남, 경기"), support);
        assertTrue(support.contains("채용 인원: 3명"), support);
        assertTrue(support.contains("학력: 학력무관"), support);
        assertTrue(support.contains("직무 분야: 경영·회계·사무, 정보통신"), support);
        assertTrue(support.contains("[지원 자격]\n만 18세 이상\n남자는 병역필"), support);
        assertTrue(support.contains("[결격 사유]"), support);
        assertFalse(support.contains("secret@example.com"), "전형 안내 글(담당자 연락처)은 싣지 않는다");
        assertFalse(support.contains("02-000-0000"));
        assertFalse(support.contains("대체인력"), "대체인력이 아니면 줄을 넣지 않는다");

        long id = idOf("990001");
        assertEquals(Set.of("48", "41"), links("policy_sido", "sido", id), "근무지 여러 값 → 시도 여러 개");
        assertEquals(Set.of("0013010"), links("policy_job", "job_code", id), "취업 상태 제한없음");
        assertEquals(Set.of("recruit.regular", "recruit.entry"), links("policy_category", "category_code", id));
        assertEquals(2, result.regionLinks());
        assertEquals(2, result.categoryLinks());
    }

    @Test
    void 고용형태_여러_값은_칩_여러_개로_붙는다() {
        insertRaw(posting("T-AN-2", "정규직,무기계약직,청년인턴(체험형)", "신입", "서울"), AT);

        normalizer.normalize();

        assertEquals(Set.of("recruit.regular", "recruit.indefinite", "recruit.intern", "recruit.entry"),
                links("policy_category", "category_code", idOf("T-AN-2")));
        assertEquals("정규직, 무기계약직, 청년인턴(체험형)", policy("T-AN-2").get("category_medium"));
    }

    @Test
    void 청년인턴_체험형과_채용형은_같은_청년인턴_칩이다() {
        insertRaw(posting("T-AN-3", "청년인턴(채용형)", "신입", "서울"), AT);
        insertRaw(posting("T-AN-4", "비정규직,청년인턴(체험형)", "신입", "서울"), AT);

        normalizer.normalize();

        assertEquals(Set.of("recruit.intern", "recruit.entry"), links("policy_category", "category_code", idOf("T-AN-3")));
        assertEquals(Set.of("recruit.contract", "recruit.intern", "recruit.entry"),
                links("policy_category", "category_code", idOf("T-AN-4")));
    }

    @Test
    void 경력만_뽑는_공고에는_신입_가능_칩을_붙이지_않는다() {
        insertRaw(posting("T-AN-5", "비정규직", "경력", "서울"), AT);

        normalizer.normalize();

        assertEquals(Set.of("recruit.contract"), links("policy_category", "category_code", idOf("T-AN-5")));
    }

    @Test
    void 통합_전_시도_이름도_같은_시도_코드로_바꾼다() {
        // 12/5: 전남광주 46 · 광주 2 · 전남 1 이 함께 왔다. 모두 우리 코드 12 하나다
        insertRaw(posting("T-AN-6", "정규직", "신입", "광주,전남광주,전남,서울"), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(Set.of("12", "11"), links("policy_sido", "sido", idOf("T-AN-6")));
        assertEquals(2, result.regionLinks());
    }

    @Test
    void 공공기관_채용_탭이_닫혀_있는_동안은_검색에_나오지_않는다() {
        // 화면 편입은 12/15. 그 전에 운영에 배포돼도 카드가 반쯤 만든 모양으로 보이지 않는다(D-018)
        insertRaw(posting("T-AN-7", "정규직", "신입", "서울"), AT);
        normalizer.normalize();

        assertTrue(find(matchRepository.match(25, "11", null, null), "T-AN-7").isEmpty());
    }

    @Test
    void 탭을_열면_이십오세_서울_검색에_나오고_나이_소득_취업_조건으로_빠지지_않는다() {
        // 12/15 에 탭을 열었을 때의 모습. 매칭 SQL 은 한 줄도 고치지 않았다(D-029 ③)
        insertRaw(posting("T-AN-8", "정규직", "신입+경력", "서울,경기"), AT);
        insertRaw(posting("T-AN-9", "비정규직", "신입", "부산"), AT);
        normalizer.normalize();
        PolicyMatchRepository opened = new PolicyMatchRepository(namedJdbc, recruitOpenCatalog());

        Optional<MatchedPolicy> found = find(opened.match(25, "11", null, null), "T-AN-8");
        assertTrue(found.isPresent(), "근무지에 서울이 있으면 서울 검색에 나온다");
        assertEquals("alio", found.get().source());
        assertEquals(IncomeStatus.UNKNOWN, found.get().incomeStatus());
        assertTrue(found.get().needsCheck(), "지원 자격 글이 있어 '확인 필요'");
        assertTrue(found.get().categories().containsAll(List.of("recruit.regular", "recruit.entry")));

        assertTrue(find(opened.match(25, "11", 3000L, "0013003"), "T-AN-8").isPresent(),
                "연소득 · 취업 상태를 넣어도 빠지지 않는다(제한없음)");
        assertTrue(find(opened.match(45, "41", null, null), "T-AN-8").isPresent(), "나이 칸이 없어 45세 · 경기에도 나온다");
        assertTrue(find(opened.match(25, "11", null, null), "T-AN-9").isEmpty(), "부산 공고는 서울에 안 나온다");
        assertTrue(find(opened.match(25, "26", null, null), "T-AN-9").isPresent());
    }

    @Test
    void 다음_수집에서_빠진_공고는_비활성된다() {
        insertRaw(posting("T-AN-10", "정규직", "신입", "서울"), AT);
        insertRaw(posting("T-AN-11", "정규직", "신입", "서울"), AT);
        normalizer.normalize();

        // 다음 날 수집: T-AN-10 만 다시 보였다(마감된 T-AN-11 은 진행 중 응답에 없음)
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('alio', ?, ?, 'SUCCESS')",
                LATER, LATER);
        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE source = 'alio' AND policy_no = 'T-AN-10'", LATER);
        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertEquals(1, result.deactivated());
        assertEquals(Boolean.TRUE, active("T-AN-10"));
        assertEquals(Boolean.FALSE, active("T-AN-11"), "지우지 않고 비활성(D-014)");
    }

    @Test
    void 같은_기관_같은_제목이어도_공고_번호가_다르면_둘_다_싣는다() {
        // 12/5: 같은 기관 · 같은 제목 15묶음 51건(재공고 · 상시 공고). 제목으로 합치지 않는다(D-029 ⑤)
        Map<String, Object> a = posting("T-AN-12", "비정규직", "신입", "경남");
        Map<String, Object> b = posting("T-AN-13", "비정규직", "신입", "경남");
        a.put("recrutPbancTtl", "계약직(야간당직약사) 채용공고");
        b.put("recrutPbancTtl", "계약직(야간당직약사) 채용공고");
        insertRaw(a, AT);
        insertRaw(b, AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(2, result.policies());
        assertEquals(0, result.duplicates());
    }

    @Test
    void 이천오십년_이후_마감은_상시로_본다() {
        // 12/5: 2020-07-21 게시 '상시모집' 공고의 마감일이 20501231 이었다
        Map<String, Object> raw = posting("T-AN-14", "비정규직", "신입+경력", "서울");
        raw.put("pbancBgngYmd", "20200721");
        raw.put("pbancEndYmd", "20501231");
        insertRaw(raw, AT);

        normalizer.normalize();

        Map<String, Object> p = policy("T-AN-14");
        assertNull(p.get("apply_end_date"));
        assertEquals("20200721 ~ 상시", p.get("apply_period_text"));
        assertEquals(LocalDate.of(2027, 12, 31), AlioNormalizer.date("20271231"), "1년 뒤 마감은 실제 날짜로 둔다");
        assertNull(AlioNormalizer.date("2026-12-31"), "형식이 다르면 읽지 않는다");
    }

    @Test
    void 대체인력_공고는_지원_내용에_적는다() {
        Map<String, Object> raw = posting("T-AN-15", "비정규직", "신입+경력", "서울");
        raw.put("replmprYn", "Y");
        insertRaw(raw, AT);

        normalizer.normalize();

        assertTrue(((String) policy("T-AN-15").get("support_content")).contains("대체인력: 예"));
    }

    @Test
    void 묶음표에_없는_고용형태와_근무지는_연결_없이_싣고_수를_센다() {
        // 새 이름이 생기면 칩 · 시도가 없어 검색에 안 나온다. 지우지 않고 싣고, 로그 · 결과 수로 알아챈다
        insertRaw(posting("T-AN-16", "파견직", "경력", "해외"), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertEquals(1, result.uncategorized());
        long id = idOf("T-AN-16");
        assertTrue(links("policy_category", "category_code", id).isEmpty());
        assertTrue(links("policy_sido", "sido", id).isEmpty());
    }

    @Test
    void 다른_출처_정책은_건드리지_않는다() {
        int othersActiveBefore = count("SELECT COUNT(*) FROM policy WHERE source <> 'alio' AND active = TRUE");
        insertRaw(posting("T-AN-17", "정규직", "신입", "서울"), AT);

        normalizer.normalize();

        assertEquals(othersActiveBefore, count("SELECT COUNT(*) FROM policy WHERE source <> 'alio' AND active = TRUE"));
    }

    @Test
    void 선정이_0건이면_비활성하지_않고_멈춘다() {
        // 성공한 수집 뒤에 받은 원본이 하나도 없다. 그대로 진행하면 활성 공고가 전부 꺼진다
        assertThrows(IllegalStateException.class, () -> normalizer.normalize());
    }

    @Test
    void 공고_원문_주소는_번호가_숫자일_때만_만든다() {
        assertEquals("https://job.alio.go.kr/recruitview.do?idx=307413", AlioNormalizer.detailUrl("307413"));
        assertNull(AlioNormalizer.detailUrl("T-AN-1"));
        assertNull(AlioNormalizer.detailUrl(null));
    }

    @Test
    void 기관_주소는_http만_쓰고_www로_시작하면_https를_붙인다() {
        // 12/5: 547건 중 28건이 http 로 시작하지 않았다(공백 · 탭 5 · www. 9 · 링크 아님 14)
        assertEquals("https://kps.co.kr", AlioNormalizer.orgUrl("https://kps.co.kr"));
        assertEquals("https://www.redcross.or.kr/redrecruit/", AlioNormalizer.orgUrl("\thttps://www.redcross.or.kr/redrecruit/"));
        assertEquals("https://www.ex.co.kr", AlioNormalizer.orgUrl("www.ex.co.kr"));
        assertNull(AlioNormalizer.orgUrl("."));
        assertNull(AlioNormalizer.orgUrl("없음."));
        assertNull(AlioNormalizer.orgUrl("/"));
        assertNull(AlioNormalizer.orgUrl("javascript:alert(1)"));
    }

    // ---------- 도우미 ----------

    /** 실제 응답에서 쓰는 칸만 채운 공고 한 건. 마감은 30일 뒤 */
    private Map<String, Object> posting(String no, String hireTypes, String recruitType, String regions) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("recrutPblntSn", no);
        raw.put("instNm", "시험공단");
        raw.put("recrutPbancTtl", "시험 채용 " + no);
        raw.put("hireTypeNmLst", hireTypes);
        raw.put("recrutSeNm", recruitType);
        raw.put("workRgnNmLst", regions);
        raw.put("recrutNope", 1);
        raw.put("pbancBgngYmd", LocalDate.now().minusDays(1).format(YYYYMMDD));
        raw.put("pbancEndYmd", LocalDate.now().plusDays(30).format(YYYYMMDD));
        raw.put("srcUrl", "https://recruit.example.or.kr/");
        raw.put("replmprYn", "N");
        raw.put("aplyQlfcCn", "자동차운전면허(2종이상)");
        raw.put("ongoingYn", "Y");
        return raw;
    }

    /** 12/15 에 공공기관 채용 탭을 열었을 때의 묶음표. 다른 탭은 이 시험과 상관없어 넣지 않는다 */
    private static CategoryCatalog recruitOpenCatalog() {
        return new CategoryCatalog(new CategoryProperties(List.of(
                new Tab("recruit", "공공기관 채용", true, List.of(
                        new Chip("regular", "정규직", List.of("정규직")),
                        new Chip("contract", "비정규직", List.of("비정규직")),
                        new Chip("entry", "신입 가능", List.of()))))));
    }

    private void insertRaw(Map<String, Object> raw, String fetchedAt) {
        jdbc.update("INSERT INTO policy_raw (source, policy_no, raw_json, fetched_at) VALUES ('alio', ?, ?, ?)",
                String.valueOf(raw.get("recrutPblntSn")), objectMapper.writeValueAsString(raw), fetchedAt);
    }

    private Map<String, Object> policy(String no) {
        return jdbc.queryForMap("SELECT * FROM policy WHERE source = 'alio' AND policy_no = ?", no);
    }

    private long idOf(String no) {
        return jdbc.queryForObject("SELECT id FROM policy WHERE source = 'alio' AND policy_no = ?", Long.class, no);
    }

    private Boolean active(String no) {
        return jdbc.queryForObject("SELECT active FROM policy WHERE source = 'alio' AND policy_no = ?", Boolean.class, no);
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
