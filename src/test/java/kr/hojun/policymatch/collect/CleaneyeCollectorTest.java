package kr.hojun.policymatch.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 지방공공기관 수집기 (12/14). 실제 API 대신 시도 코드별로 정해진 XML 을 돌려주는 가짜 클라이언트를 쓰고,
 * DB 는 실제 MySQL 에 쓴 뒤 롤백한다.
 */
@SpringBootTest
@Transactional
class CleaneyeCollectorTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PolicyRawStore store;
    @Autowired CollectRunStore runs;

    @Test
    void 시도_코드마다_한_번씩_부르고_모두_응답하면_SUCCESS다() {
        // 결과 없음(3)은 그 시도에 공고가 없다는 정상 응답이다(12/5: 007005 · 007018 ~ 020)
        FakeClient fake = fake(Map.of("A", ok("T-C-1", "T-C-2"), "B", NO_DATA, "C", ok("T-C-3")));

        CollectResult result = collector(fake, "A", "B", "C").collectAll();

        assertEquals(3, result.itemsSeen());
        assertEquals(3, result.pagesRead(), "부른 시도 코드 수");
        Map<String, Object> run = lastRun();
        assertEquals("cleaneye", run.get("source"));
        assertEquals("SUCCESS", run.get("status"));
        assertEquals(3, ((Number) run.get("tot_count")).intValue(), "응답의 totalCount(늘 0) 대신 받은 행 수");
        assertEquals(3, testRows());
    }

    @Test
    void 요청에는_시도_코드와_XML_형식만_넣고_페이지_파라미터는_없다() {
        FakeClient fake = fake(Map.of("007001", ok("T-C-1")));

        collector(fake, "007001").collectAll();

        assertEquals(1, fake.requests.size());
        Map<String, String> params = fake.requests.get(0);
        assertEquals("007001", params.get("sidoCd"));
        assertEquals("xml", params.get("type"));
        assertEquals(2, params.size(), "pageNo · numOfRows 없음");
    }

    @Test
    void 원본은_칸_이름_그대로_JSON으로_넣고_null_글자도_남긴다() {
        collector(fake(Map.of("A", ok("T-C-1"))), "A").collectAll();

        String raw = jdbc.queryForObject(
                "SELECT raw_json FROM policy_raw WHERE source = 'cleaneye' AND policy_no = 'T-C-1'", String.class);
        assertTrue(raw.contains("\"ENT_NAME\""), raw);
        assertTrue(raw.contains("서울특별시"), raw);
        assertTrue(raw.replace(" ", "").contains("\"WORK_PLACE\":\"null\""), "값은 손대지 않는다: " + raw);
    }

    @Test
    void 두_번_받아도_원본_행이_늘지_않는다() {
        collector(fake(Map.of("A", ok("T-C-1", "T-C-2"))), "A").collectAll();
        collector(fake(Map.of("A", ok("T-C-1", "T-C-2"))), "A").collectAll();

        assertEquals(2, testRows());
    }

    @Test
    void 한_시도라도_실패하면_FAILED로_기록하고_예외를_던진다() {
        // 그대로 성공 처리하면 그 시도의 공고가 통째로 비활성된다(D-029 ④: 모든 시도가 응답해야 성공)
        FakeClient fake = fake(Map.of("A", ok("T-C-1"), "C", ok("T-C-3")));
        fake.failCode = "B";

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> collector(fake, "A", "B", "C").collectAll());

        assertTrue(e.getMessage().contains("가짜 실패"), e.getMessage());
        Map<String, Object> run = lastRun();
        assertEquals("FAILED", run.get("status"));
        assertEquals(1, ((Number) run.get("items_seen")).intValue(), "A 시도분만 저장됨");
        assertEquals(2, fake.requests.size(), "실패한 뒤로는 부르지 않는다");
    }

    @Test
    void 성공이_아닌_결과_코드면_코드와_메시지를_담아_실패한다() {
        String error = "<response><openXmlEmployInfo-header><resultCode>11</resultCode>"
                + "<resultMsg>NOTEXISTPARAMETER_ERROR(type)</resultMsg></openXmlEmployInfo-header></response>";

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> collector(fake(Map.of("A", error)), "A").collectAll());

        assertTrue(e.getMessage().contains("resultCode=11"), e.getMessage());
        assertTrue(e.getMessage().contains("NOTEXISTPARAMETER_ERROR"), e.getMessage());
        assertEquals("FAILED", lastRun().get("status"));
    }

    @Test
    void 공공데이터포털_게이트웨이_오류도_실패로_읽는다() {
        // 인증키 · 한도 오류는 결과 코드 없이 OpenAPI_ServiceResponse 모양으로 온다
        String gateway = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
                + "<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode>"
                + "</cmmMsgHeader></OpenAPI_ServiceResponse>";

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> collector(fake(Map.of("A", gateway)), "A").collectAll());

        assertTrue(e.getMessage().contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR"), e.getMessage());
        assertEquals(0, testRows());
    }

    @Test
    void 모든_시도가_결과_없음이면_실패로_본다() {
        // 지방공공기관 전체에 채용 공고가 0건일 수는 없다. 성공으로 받으면 정규화가 공고를 모두 끄려 한다
        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> collector(fake(Map.of("A", NO_DATA, "B", NO_DATA)), "A", "B").collectAll());

        assertTrue(e.getMessage().contains("0건"), e.getMessage());
        assertEquals("FAILED", lastRun().get("status"));
    }

    @Test
    void DOCTYPE이_든_XML은_읽지_않는다() {
        // 외부 엔티티로 서버 파일을 읽게 하는 공격(XXE)을 막는다. 공공데이터포털 응답에는 DOCTYPE 이 없다
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<response><openXmlEmployInfo-header><resultCode>0</resultCode></openXmlEmployInfo-header>"
                + "<openXmlEmployInfo-body><items><item><NO>T-C-9</NO><ENT_NAME>&x;</ENT_NAME></item></items>"
                + "</openXmlEmployInfo-body></response>";

        assertThrows(ExternalApiException.class, () -> CleaneyeCollector.parse(xxe, "sidoCd=A"));
    }

    @Test
    void 온통청년의_마지막_성공_수집_시각을_바꾸지_않는다() {
        Optional<String> youthBefore = runs.lastSuccessStartedAt(Source.YOUTH);

        collector(fake(Map.of("A", ok("T-C-1"))), "A").collectAll();

        assertEquals(youthBefore, runs.lastSuccessStartedAt(Source.YOUTH));
        assertTrue(runs.lastSuccessStartedAt(Source.CLEANEYE).isPresent());
    }

    // ---------- 도우미 ----------

    /** 12/5 실제 모양: 결과 없음 */
    private static final String NO_DATA = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<response>\n<openXmlEmployInfo-header>\n"
            + "<resultCode>3</resultCode>\n<resultMsg>NONOTEXISTDATA_ERROR</resultMsg>\n</openXmlEmployInfo-header>\n</response>";

    /** 12/5 실제 모양: 성공. totalCount 는 항목이 있어도 0 이다 */
    private static String ok(String... nos) {
        StringBuilder items = new StringBuilder();
        for (String no : nos) {
            items.append("<item><ENT_NAME>시험공단</ENT_NAME><ENT_TITLE>시험 채용 ").append(no).append("</ENT_TITLE>")
                    .append("<SIDO_CD>서울특별시</SIDO_CD><JOB_TYPE>기간제</JOB_TYPE><WORK_PLACE>null</WORK_PLACE>")
                    .append("<NO>").append(no).append("</NO></item>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><response><openXmlEmployInfo-header><resultCode>0</resultCode>"
                + "<resultMsg>SUCCESS_INFO</resultMsg></openXmlEmployInfo-header><openXmlEmployInfo-body><items>"
                + items + "</items><totalCount>0</totalCount></openXmlEmployInfo-body></response>";
    }

    private CleaneyeCollector collector(FakeClient fake, String... codes) {
        CleaneyeProperties props = new CleaneyeProperties();
        props.setSidoCodes(List.of(codes));
        return new CleaneyeCollector(fake, store, runs, props, objectMapper);
    }

    private FakeClient fake(Map<String, String> bodies) {
        DataGoKrProperties props = new DataGoKrProperties();
        props.setKey("test");
        return new FakeClient(props, objectMapper, bodies);
    }

    private Map<String, Object> lastRun() {
        return jdbc.queryForMap("SELECT source, status, tot_count, items_seen FROM collect_run ORDER BY id DESC LIMIT 1");
    }

    private int testRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_raw WHERE source = 'cleaneye' AND policy_no LIKE 'T-C-%'", Integer.class);
        return n == null ? 0 : n;
    }

    /** 시도 코드별로 정해진 XML 을 돌려준다. 정해 두지 않은 코드는 결과 없음. failCode 는 호출 실패. */
    static class FakeClient extends DataGoKrClient {

        final List<Map<String, String>> requests = new ArrayList<>();
        String failCode;
        private final Map<String, String> bodies;

        FakeClient(DataGoKrProperties props, ObjectMapper mapper, Map<String, String> bodies) {
            super(props, mapper);
            this.bodies = new HashMap<>(bodies);
        }

        @Override
        public String getXml(Source source, String url, Map<String, String> params, String label) {
            requests.add(Map.copyOf(params));
            String code = params.get("sidoCd");
            if (code.equals(failCode)) {
                throw new ExternalApiException(source, "가짜 실패 (" + label + ")");
            }
            return bodies.getOrDefault(code, NO_DATA);
        }
    }
}
