package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
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
 * 지방공공기관 정규화 (12/14). 실제 MySQL 에 시험 원본을 넣고 정규화 → 매칭까지 확인한 뒤 롤백한다.
 *
 * 시험 원본은 2099년 수집으로 넣는다. 정규화는 "마지막 성공 수집 이후"만 고르므로, 로컬 DB 의 실제 원본은 섞이지 않는다.
 * 로컬 DB 의 실제 지방공공기관 공고는 setUp 에서 먼저 비활성으로 둔다(롤백됨). 그대로 두면 정규화가 그것을 전부 끄려다
 * 대량 비활성 상한에 걸린다(잡알리오 시험과 같은 이유).
 */
@SpringBootTest
@Transactional
class CleaneyeNormalizerTest {

    private static final String AT = "2099-01-02 00:00:00";
    private static final String LATER = "2099-01-03 00:00:00";

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CleaneyeNormalizer normalizer;
    @Autowired PolicyMatchRepository matchRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE policy SET active = FALSE WHERE source = 'cleaneye' AND active = TRUE AND policy_no NOT LIKE 'T-%'");
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('cleaneye', ?, ?, 'SUCCESS')",
                AT, AT);
    }

    @Test
    void 원본_칸을_policy_칸으로_옮긴다() {
        Map<String, String> raw = row("T-CN-1", "용산문화재단", "2026년 제4차 직원 공개경쟁채용", "기간제", "신입+경력", "서울특별시");
        raw.put("PUB_DATE", "2026-12-01");
        raw.put("PUB_END_DATE", "2026-12-31");
        raw.put("POSITION", "주임(8급)");
        raw.put("ENT_RECRUIT", "경영·회계·사무");
        raw.put("EMPLOY_NUM", "2");
        raw.put("YEARINCOME", "2800만원 이상");
        raw.put("ENT_GB", "지방출연기관");
        raw.put("ENT_KIND", "문화·복지·관광");
        raw.put("LOCAL_YN", "Y");
        raw.put("LOCAL_NAME", "서울특별시 용산구");
        raw.put("LOCAL_REASON", "지역인재 채용");
        raw.put("ENT_LICENSE1", "컴퓨터활용능력");
        raw.put("SPECIAL_ITEM", "국가유공자");
        raw.put("DUTY_DETAIL", "회계 &amp; 예산");
        raw.put("EXHIBIT", "응시원서, 자기소개서");
        raw.put("ACCUSATION", "이메일 접수 secret@example.or.kr");
        raw.put("REFERENCE", "02-000-0000");
        raw.put("JOB_SEEK_ETC", "문의 010-0000-0000");
        insertRaw(raw, AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.candidates());
        assertEquals(1, result.policies());
        Map<String, Object> p = policy("T-CN-1");
        assertEquals("2026년 제4차 직원 공개경쟁채용", p.get("title"), "행이 하나뿐인 공고는 제목 그대로");
        assertEquals("공공기관 채용", p.get("category_large"));
        assertEquals("기간제", p.get("category_medium"));
        assertNull(p.get("min_age"), "나이 칸이 없다(D-029 ③)");
        assertNull(p.get("earn_cnd_se_cd"), "소득 칸은 비운다");
        assertEquals("20261201 ~ 20261231", p.get("apply_period_text"), "다른 출처와 같은 날짜 모양");
        assertEquals("2026-12-31", String.valueOf(p.get("apply_end_date")));
        assertEquals("https://job.cleaneye.go.kr/user/ypCareersData.do?idx=T-CN-1", p.get("apply_url"), "신청하기 = 클린아이 원문");
        assertNull(p.get("ref_url"));
        assertEquals("용산문화재단", p.get("supervising_org"));
        assertEquals("지역 제한: 서울특별시 용산구 (지역인재 채용)\n필요 자격: 컴퓨터활용능력", p.get("extra_qualification"));
        assertEquals("우대: 국가유공자", p.get("etc_matters"));
        assertEquals("응시원서, 자기소개서", p.get("required_documents"));

        String support = (String) p.get("support_content");
        assertTrue(support.contains("고용형태: 기간제"), support);
        assertTrue(support.contains("채용 구분: 신입 + 경력"), support);
        assertTrue(support.contains("직급 · 직위: 주임(8급)"), support);
        assertTrue(support.contains("채용 인원: 2명"), support);
        assertTrue(support.contains("연봉: 2800만원 이상"), support);
        assertTrue(support.contains("기관: 용산문화재단 (지방출연기관 · 문화·복지·관광)"), support);
        assertTrue(support.contains("[직무 내용]\n회계 & 예산"), support);
        assertFalse(support.contains("secret@example.or.kr"), "접수 방법의 담당 메일은 싣지 않는다");
        assertFalse(support.contains("02-000-0000"), "문의처 전화는 싣지 않는다");
        assertFalse(support.contains("010-0000-0000"), "기타 글은 싣지 않는다");
        assertFalse(support.contains("null"), "'null' 글자는 빈 값이다");

        long id = idOf("T-CN-1");
        assertEquals(Set.of("11"), links("policy_sido", "sido", id));
        assertEquals(Set.of("0013010"), links("policy_job", "job_code", id), "취업 상태 제한없음");
        assertEquals(Set.of("recruit.contract", "recruit.entry"), links("policy_category", "category_code", id));
    }

    @Test
    void 지방공공기관_고용형태_이름을_잡알리오와_같은_칩으로_모은다() {
        // 12/5: 일반정규직 ↔ 정규직, 기간제 · 일반계약직 · 전문계약직 ↔ 비정규직, 인턴 ↔ 청년인턴
        insertRaw(row("T-CN-2", "기관A", "공고A", "일반정규직", "경력", "서울특별시"), AT);
        insertRaw(row("T-CN-3", "기관B", "공고B", "전문계약직", "경력", "서울특별시"), AT);
        insertRaw(row("T-CN-4", "기관C", "공고C", "인턴", "신입", "서울특별시"), AT);
        insertRaw(row("T-CN-5", "기관D", "공고D", "무기계약직", "경력", "서울특별시"), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(Set.of("recruit.regular"), links("policy_category", "category_code", idOf("T-CN-2")));
        assertEquals(Set.of("recruit.contract"), links("policy_category", "category_code", idOf("T-CN-3")));
        assertEquals(Set.of("recruit.intern", "recruit.entry"), links("policy_category", "category_code", idOf("T-CN-4")));
        assertEquals(Set.of("recruit.indefinite"), links("policy_category", "category_code", idOf("T-CN-5")));
        assertEquals(0, result.uncategorized());
    }

    @Test
    void 비상임_임원_공모는_싣지_않고_건너뛴_수로_센다() {
        insertRaw(row("T-CN-6", "시설관리공단", "비상임이사 공개모집", "비상임", "경력", "서울특별시"), AT);
        insertRaw(row("T-CN-7", "시설관리공단", "직원 채용", "기간제", "신입", "서울특별시"), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertEquals(1, result.skipped());
        assertEquals(0, count("SELECT COUNT(*) FROM policy WHERE source = 'cleaneye' AND policy_no = 'T-CN-6'"));
    }

    @Test
    void 시도_정식_이름을_시도_코드로_바꾼다() {
        insertRaw(row("T-CN-8", "기관E", "공고E", "기간제", "신입", "전남광주통합특별시"), AT);
        insertRaw(row("T-CN-9", "기관F", "공고F", "기간제", "신입", "강원특별자치도"), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(Set.of("12"), links("policy_sido", "sido", idOf("T-CN-8")));
        assertEquals(Set.of("51"), links("policy_sido", "sido", idOf("T-CN-9")));
        assertEquals(2, result.regionLinks());
    }

    @Test
    void 같은_공고의_여러_행은_달라지는_값을_제목에_붙인다() {
        // 12/5: 같은 (기관, 제목)이 직급 · 직무별로 최대 9행. 행마다 번호가 달라 합치지 않고 카드가 구분되게 한다
        Map<String, String> a = row("T-CN-10", "용산문화재단", "제4차 채용", "기간제", "신입", "서울특별시");
        Map<String, String> b = row("T-CN-11", "용산문화재단", "제4차 채용", "기간제", "신입", "서울특별시");
        Map<String, String> c = row("T-CN-12", "용산문화재단", "제4차 채용", "기간제", "신입", "서울특별시");
        a.put("POSITION", "대리(7급)");
        b.put("POSITION", "주임(8급)");
        c.put("POSITION", "주임(8급)");
        Map<String, String> d = row("T-CN-13", "의왕도시공사", "기간제근로자 채용", "기간제", "신입", "경기도");
        Map<String, String> e = row("T-CN-14", "의왕도시공사", "기간제근로자 채용", "기간제", "신입", "경기도");
        d.put("ENT_RECRUIT", "경비·청소");
        e.put("ENT_RECRUIT", "경영·회계·사무");
        for (Map<String, String> r : List.of(a, b, c, d, e)) {
            insertRaw(r, AT);
        }

        normalizer.normalize();

        assertEquals("제4차 채용 · 대리(7급)", policy("T-CN-10").get("title"));
        assertEquals("제4차 채용 · 주임(8급) (1)", policy("T-CN-11").get("title"), "그래도 같으면 번호 순");
        assertEquals("제4차 채용 · 주임(8급) (2)", policy("T-CN-12").get("title"));
        assertEquals("기간제근로자 채용 · 경비·청소", policy("T-CN-13").get("title"), "직급이 '-'면 직무 분야");
        assertEquals("기간제근로자 채용 · 경영·회계·사무", policy("T-CN-14").get("title"));
    }

    @Test
    void 공공기관_채용_탭이_닫혀_있는_동안은_검색에_나오지_않는다() {
        insertRaw(row("T-CN-15", "기관G", "공고G", "기간제", "신입", "서울특별시"), AT);
        normalizer.normalize();

        assertTrue(find(matchRepository.match(25, "11", null, null), "T-CN-15").isEmpty());
    }

    @Test
    void 탭을_열면_이십오세_서울_검색에_나오고_자격_조건이_없으면_확인_필요가_없다() {
        insertRaw(row("T-CN-16", "기관H", "공고H", "일반정규직", "신입", "서울특별시"), AT);
        insertRaw(row("T-CN-17", "기관I", "공고I", "기간제", "신입", "부산광역시"), AT);
        normalizer.normalize();
        PolicyMatchRepository opened = new PolicyMatchRepository(namedJdbc, recruitOpenCatalog());

        Optional<MatchedPolicy> found = find(opened.match(25, "11", 3000L, "0013003"), "T-CN-16");
        assertTrue(found.isPresent(), "나이 · 소득 · 취업 상태로 빠지지 않는다");
        assertEquals("cleaneye", found.get().source());
        assertEquals(IncomeStatus.UNKNOWN, found.get().incomeStatus());
        assertFalse(found.get().needsCheck(), "지역 제한 · 자격증이 없으면 '확인 필요'가 없다");
        assertTrue(found.get().categories().containsAll(List.of("recruit.regular", "recruit.entry")));
        assertTrue(find(opened.match(25, "11", null, null), "T-CN-17").isEmpty(), "부산 공고는 서울에 안 나온다");
        assertTrue(find(opened.match(25, "26", null, null), "T-CN-17").isPresent());
    }

    @Test
    void 다음_수집에서_빠진_행은_비활성된다() {
        insertRaw(row("T-CN-18", "기관J", "공고J", "기간제", "신입", "서울특별시"), AT);
        insertRaw(row("T-CN-19", "기관K", "공고K", "기간제", "신입", "서울특별시"), AT);
        normalizer.normalize();

        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('cleaneye', ?, ?, 'SUCCESS')",
                LATER, LATER);
        jdbc.update("UPDATE policy_raw SET fetched_at = ? WHERE source = 'cleaneye' AND policy_no = 'T-CN-18'", LATER);
        NormalizeResult result = normalizer.normalize();

        assertEquals(1, result.policies());
        assertEquals(1, result.deactivated());
        assertEquals(Boolean.TRUE, active("T-CN-18"));
        assertEquals(Boolean.FALSE, active("T-CN-19"), "지우지 않고 비활성(D-014)");
    }

    @Test
    void 이천오십년_이후_마감은_상시로_본다() {
        // 12/5: 9999-09-09 · 2400-03-19 · 2054-07-10 · 2050-09-26
        Map<String, String> raw = row("T-CN-20", "기관L", "공고L", "기간제", "신입", "서울특별시");
        raw.put("PUB_DATE", "2026-11-23");
        raw.put("PUB_END_DATE", "9999-09-09");
        insertRaw(raw, AT);

        normalizer.normalize();

        Map<String, Object> p = policy("T-CN-20");
        assertNull(p.get("apply_end_date"));
        assertEquals("20261123 ~ 상시", p.get("apply_period_text"));
        assertEquals(LocalDate.of(2026, 12, 31), CleaneyeNormalizer.date("2026-12-31"));
        assertNull(CleaneyeNormalizer.date("2050-09-26"));
        assertNull(CleaneyeNormalizer.date("20261231"), "형식이 다르면 읽지 않는다");
    }

    @Test
    void 잡알리오와_기관_이름이_같아도_합치지_않고_센다() {
        // D-029 ⑤: 같은 식별자가 없으면 합치지 않는다. 12/5 기관 이름 겹침 0 — 겹치면 로그로 알아챈다
        jdbc.update("INSERT INTO policy (source, policy_no, title, supervising_org, apply_end_date) VALUES ('alio', ?, ?, ?, ?)",
                "T-CN-A1", "2026년 직원 채용", "(재)시험문화재단", java.sql.Date.valueOf(LocalDate.now().plusDays(10)));
        insertRaw(row("T-CN-21", "재단법인 시험문화재단", "2026년 직원 채용", "기간제", "신입", "서울특별시"), AT);

        NormalizeResult result = normalizer.normalize();

        assertEquals(0, result.duplicates());
        assertEquals(Boolean.TRUE, active("T-CN-21"));
        CleaneyeNormalizer.Overlap overlap = normalizer.overlapWithAlio(List.of(new CleaneyeNormalizer.Unit(
                "T-CN-21", null, "재단법인 시험문화재단", "2026년 직원 채용", null, null, "기간제", "신입")));
        assertEquals(1, overlap.sameOrg());
        assertEquals(1, overlap.sameOrgTitle());
    }

    @Test
    void 기관_이름은_법인_표기와_공백을_빼고_비교한다() {
        assertEquals("양천문화재단", CleaneyeNormalizer.orgKey("재단법인 양천문화재단"));
        assertEquals("양천문화재단", CleaneyeNormalizer.orgKey("(재)양천문화재단"));
        assertEquals("한전KPS", CleaneyeNormalizer.orgKey("한전KPS(주)"));
    }

    @Test
    void 다른_출처_정책은_건드리지_않는다() {
        int othersActiveBefore = count("SELECT COUNT(*) FROM policy WHERE source <> 'cleaneye' AND active = TRUE");
        insertRaw(row("T-CN-22", "기관M", "공고M", "기간제", "신입", "서울특별시"), AT);

        normalizer.normalize();

        assertEquals(othersActiveBefore, count("SELECT COUNT(*) FROM policy WHERE source <> 'cleaneye' AND active = TRUE"));
    }

    @Test
    void 선정이_0건이면_비활성하지_않고_멈춘다() {
        assertThrows(IllegalStateException.class, () -> normalizer.normalize());
    }

    // ---------- 도우미 ----------

    /** 실제 응답처럼 모든 값이 글자이고, 비어 있는 칸은 '-' · 'null' 글자다. 마감은 30일 뒤 */
    private Map<String, String> row(String no, String org, String title, String jobType, String employGb, String sido) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("ENT_NAME", org);
        raw.put("ENT_TITLE", title);
        raw.put("ENT_GB", "지방공단");
        raw.put("ENT_KIND", "시설관리공단");
        raw.put("SIDO_CD", sido);
        raw.put("STATUS", "모집중");
        raw.put("EMPLOY_NUM", "1");
        raw.put("EMPLOY_GB", employGb);
        raw.put("JOB_TYPE", jobType);
        raw.put("RE_MAN_YN", "N");
        raw.put("POSITION", "-");
        raw.put("ENT_RECRUIT", "기타");
        raw.put("YEARINCOME", "2200만원 이상");
        raw.put("OFFICE_HOURS", "null");
        raw.put("WORK_PLACE", "null");
        raw.put("DUTY_DETAIL", "-");
        raw.put("RETIREMENT", "60");
        raw.put("LOCAL_YN", "N");
        raw.put("LOCAL_NAME", "-");
        raw.put("ENT_LICENSE1", "-");
        raw.put("SPECIAL_ITEM", "null");
        raw.put("PUB_DATE", LocalDate.now().minusDays(1).toString());
        raw.put("PUB_END_DATE", LocalDate.now().plusDays(30).toString());
        raw.put("URL", "https://job.cleaneye.go.kr/user/ypCareersData.do?idx=" + no);
        raw.put("NO", no);
        return raw;
    }

    /** 12/15 에 공공기관 채용 탭을 열었을 때의 묶음표. 이 시험에 필요한 칩만 넣는다 */
    private static CategoryCatalog recruitOpenCatalog() {
        return new CategoryCatalog(new CategoryProperties(List.of(
                new Tab("recruit", "공공기관 채용", true, List.of(
                        new Chip("regular", "정규직", List.of("정규직", "일반정규직")),
                        new Chip("contract", "비정규직", List.of("비정규직", "기간제")),
                        new Chip("entry", "신입 가능", List.of()))))));
    }

    private void insertRaw(Map<String, String> raw, String fetchedAt) {
        jdbc.update("INSERT INTO policy_raw (source, policy_no, raw_json, fetched_at) VALUES ('cleaneye', ?, ?, ?)",
                raw.get("NO"), objectMapper.writeValueAsString(raw), fetchedAt);
    }

    private Map<String, Object> policy(String no) {
        return jdbc.queryForMap("SELECT * FROM policy WHERE source = 'cleaneye' AND policy_no = ?", no);
    }

    private long idOf(String no) {
        return jdbc.queryForObject("SELECT id FROM policy WHERE source = 'cleaneye' AND policy_no = ?", Long.class, no);
    }

    private Boolean active(String no) {
        return jdbc.queryForObject("SELECT active FROM policy WHERE source = 'cleaneye' AND policy_no = ?",
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
