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
    void 나이제한_없음_Y는_어떤_나이든_포함한다() {
        // 0/0 을 그대로 비교했다면 모든 나이에서 빠졌을 정책
        assertTrue(titles(0, null).contains("T-NO-AGE-LIMIT"));
        assertTrue(titles(120, null).contains("T-NO-AGE-LIMIT"));
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

    // ---------- 도우미 ----------

    private void insert(String no, String ageLimitYn, int minAge, int maxAge,
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
