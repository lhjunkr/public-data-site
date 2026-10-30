package kr.hojun.policymatch.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매칭 SQL 경계값 테스트. 실제 MySQL 에 시험용 정책을 넣고 SQL 을 돌린다.
 *
 * - @Transactional: 테스트가 끝나면 넣은 데이터가 전부 롤백된다. 로컬 DB 의 실제 데이터를 건드리지 않는다.
 * - 시험용 시도 코드 "99" 를 쓴다. 실제로는 없는 코드라 실제 정책이 결과에 섞이지 않는다.
 * - 날짜는 Java 의 오늘이 아니라 DB 의 CURDATE() 기준으로 넣는다.
 *   SQL 이 CURDATE() 로 비교하므로, 앱과 DB 의 시간대가 달라도(자정 전후) 테스트가 흔들리지 않는다.
 */
@SpringBootTest
@Transactional
class PolicyMatchRepositoryTest {

    private static final String SIDO = "99";

    @Autowired JdbcTemplate jdbc;
    @Autowired PolicyMatchRepository repository;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO region (code, sido, name) VALUES "
                + "('99001','99','시험구1'), ('99002','99','시험구2'), ('98001','98','다른시')");

        //          정책번호           나이제한 min max  소득코드    하한   상한    마감(오늘+n일)  연결 지역
        insert("T-AGE-19-39",    "N", 19, 39, "0043001", 0L,   0L,    null,  "99001", "99002");
        insert("T-NO-AGE-LIMIT", "Y", 0,  0,  "0043001", 0L,   0L,    null,  "99001");
        insert("T-END-TODAY",    "Y", 0,  0,  "0043001", 0L,   0L,    0,     "99001");
        insert("T-END-YESTERDAY","Y", 0,  0,  "0043001", 0L,   0L,    -1,    "99001");
        insert("T-INCOME-5000",  "Y", 0,  0,  "0043002", null, 5000L, null,  "99001");
        insert("T-INCOME-ETC",   "Y", 0,  0,  "0043003", 0L,   0L,    null,  "99001");
        insert("T-INCOME-NULL",  "Y", 0,  0,  null,      null, null,  null,  "99001");
        insert("T-OTHER-SIDO",   "Y", 0,  0,  "0043001", 0L,   0L,    null,  "98001");
        // D-015 · D-016 (10/30 전체 데이터 측정에서 나온 경우들)
        insert("T-N-ZERO",       "N", 0,  0,  "0043001", 0L,   0L,    null,  "99001");
        insert("T-Y-19-34",      "Y", 19, 34, "0043001", 0L,   0L,    null,  "99001");
        insert("T-18-UP",        "N", 18, 0,  "0043001", 0L,   0L,    null,  "99001");
        insert("T-AGE-NULL",     "Y", null, null, "0043001", 0L, 0L,  null,  "99001");
        insert("T-AGE-FLIP",     "N", 39, 19, "0043001", 0L,   0L,    null,  "99001");
        insert("T-INCOME-NOAMT", "Y", 0,  0,  "0043002", 0L,   0L,    null,  "99001");
        insert("T-INCOME-MIN",   "Y", 0,  0,  "0043002", 4000L, 6000L, null, "99001");
    }

    // ---------- 나이 ----------

    @Test
    void 나이_하한과_상한은_포함하고_바깥은_제외한다() {
        assertFalse(titles(18, null).contains("T-AGE-19-39"));
        assertTrue(titles(19, null).contains("T-AGE-19-39"));
        assertTrue(titles(39, null).contains("T-AGE-19-39"));
        assertFalse(titles(40, null).contains("T-AGE-19-39"));
    }

    @Test
    void 나이_0_0은_표시값과_상관없이_어떤_나이든_포함한다() {
        // 9/25 규칙은 Y 만 나이 무관으로 봤다. N + 0/0 은 0세가 아니면 절대 나오지 않았다(10/30 측정 16건)
        for (int age : new int[] {0, 25, 120}) {
            Set<String> result = titles(age, null);
            assertTrue(result.contains("T-NO-AGE-LIMIT"), "Y 0/0, " + age + "세");
            assertTrue(result.contains("T-N-ZERO"), "N 0/0, " + age + "세");
        }
    }

    @Test
    void Y여도_나이_범위가_있으면_범위로_비교한다() {
        // 10/30 측정: Y 306건 중 156건이 범위를 가졌고, 원문에도 같은 나이가 적혀 있었다
        assertFalse(titles(18, null).contains("T-Y-19-34"));
        assertTrue(titles(19, null).contains("T-Y-19-34"));
        assertTrue(titles(34, null).contains("T-Y-19-34"));
        assertFalse(titles(35, null).contains("T-Y-19-34"));
    }

    @Test
    void 최대_0은_상한_없음이다() {
        // 원문 예: "만 18세가 되어 보호가 종료된 아동" → 18세 이상
        assertFalse(titles(17, null).contains("T-18-UP"));
        assertTrue(titles(18, null).contains("T-18-UP"));
        assertTrue(titles(120, null).contains("T-18-UP"));
    }

    @Test
    void 나이를_판단할_수_없으면_포함한다() {
        // 값이 없거나 최소 > 최대로 뒤집힌 경우 — 빼면 받을 수 있는 정책을 놓친다(D-001)
        Set<String> result = titles(25, null);
        assertTrue(result.contains("T-AGE-NULL"));
        assertTrue(result.contains("T-AGE-FLIP"));
    }

    // ---------- 마감 ----------

    @Test
    void 마감일_당일은_포함하고_어제는_제외한다() {
        Set<String> result = titles(25, null);
        assertTrue(result.contains("T-END-TODAY"));
        assertFalse(result.contains("T-END-YESTERDAY"));
        assertTrue(result.contains("T-NO-AGE-LIMIT"), "마감일이 없는(상시) 정책은 포함");
    }

    // ---------- 소득 3-state ----------

    @Test
    void 소득_상한은_포함하고_넘으면_미충족() {
        assertEquals(IncomeStatus.MET,     statusOf("T-INCOME-5000", 0L));
        assertEquals(IncomeStatus.MET,     statusOf("T-INCOME-5000", 5000L));
        assertEquals(IncomeStatus.NOT_MET, statusOf("T-INCOME-5000", 5001L));
    }

    @Test
    void 소득을_입력하지_않으면_금액비교_정책은_판단불가() {
        assertEquals(IncomeStatus.UNKNOWN, statusOf("T-INCOME-5000", null));
    }

    @Test
    void 소득코드별_라벨() {
        assertEquals(IncomeStatus.MET,     statusOf("T-NO-AGE-LIMIT", 3000L));  // 0043001 무관
        assertEquals(IncomeStatus.UNKNOWN, statusOf("T-INCOME-ETC", 3000L));    // 0043003 기타
        assertEquals(IncomeStatus.UNKNOWN, statusOf("T-INCOME-NULL", 3000L));   // 코드 없음
    }

    @Test
    void 금액비교인데_상한이_없으면_판단불가() {
        // 10/30 측정: 상한 0 인 정책이 예전에는 모두에게 "미충족"이었다
        assertEquals(IncomeStatus.UNKNOWN, statusOf("T-INCOME-NOAMT", 0L));
        assertEquals(IncomeStatus.UNKNOWN, statusOf("T-INCOME-NOAMT", 3000L));
    }

    @Test
    void 금액비교에_하한이_있으면_상한_이하는_판단불가_넘으면_미충족() {
        // 하한 칸에 다른 대상의 기준이 들어간 사례(청년 4,000 / 신혼부부 6,000) — 상한 이하는 판단할 수 없다
        assertEquals(IncomeStatus.UNKNOWN, statusOf("T-INCOME-MIN", 3000L));
        assertEquals(IncomeStatus.UNKNOWN, statusOf("T-INCOME-MIN", 6000L));
        assertEquals(IncomeStatus.NOT_MET, statusOf("T-INCOME-MIN", 6001L));
    }

    @Test
    void 기타_소득조건은_원문을_함께_준다() {
        assertNotNull(find("T-INCOME-ETC", null).incomeNote());
    }

    // ---------- 지역 ----------

    @Test
    void 다른_시도의_정책은_제외한다() {
        assertFalse(titles(25, null).contains("T-OTHER-SIDO"));
    }

    @Test
    void 같은_시도의_구가_여러개여도_한번만_나온다() {
        // JOIN 이었다면 2줄, EXISTS 라서 1줄
        long count = repository.match(25, SIDO, null).stream()
                .filter(p -> p.title().equals("T-AGE-19-39"))
                .count();
        assertEquals(1, count);
    }

    // ---------- 활성 여부 (D-014) ----------

    @Test
    void 비활성_정책은_다른_조건이_맞아도_제외한다() {
        insert("T-INACTIVE", "Y", 0, 0, "0043001", 0L, 0L, null, "99001");
        assertTrue(titles(25, null).contains("T-INACTIVE"), "활성일 때는 나온다");

        jdbc.update("UPDATE policy SET active = FALSE WHERE policy_no = 'T-INACTIVE'");

        assertFalse(titles(25, null).contains("T-INACTIVE"));
    }

    // ---------- 도우미 ----------

    private void insert(String no, String ageLimitYn, Integer minAge, Integer maxAge,
                        String earnCd, Long earnMin, Long earnMax, Integer endOffsetDays,
                        String... regionCodes) {
        jdbc.update("""
                INSERT INTO policy (policy_no, title, age_limit_yn, min_age, max_age,
                                    earn_cnd_se_cd, earn_min_amt, earn_max_amt, earn_etc_cn,
                                    apply_end_date, view_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '시험용 기타 소득조건 원문',
                        IF(? IS NULL, NULL, DATE_ADD(CURDATE(), INTERVAL ? DAY)), 0)
                """,
                no, no, ageLimitYn, minAge, maxAge, earnCd, earnMin, earnMax, endOffsetDays, endOffsetDays);
        Long id = jdbc.queryForObject("SELECT id FROM policy WHERE policy_no = ?", Long.class, no);
        for (String code : regionCodes) {
            jdbc.update("INSERT INTO policy_region (policy_id, region_code) VALUES (?, ?)", id, code);
        }
    }

    private Set<String> titles(int age, Long income) {
        return repository.match(age, SIDO, income).stream()
                .map(MatchedPolicy::title)
                .collect(Collectors.toSet());
    }

    private MatchedPolicy find(String title, Long income) {
        List<MatchedPolicy> all = repository.match(25, SIDO, income);
        return all.stream().filter(p -> p.title().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError(title + " 이(가) 결과에 없음"));
    }

    private IncomeStatus statusOf(String title, Long income) {
        return find(title, income).incomeStatus();
    }
}
