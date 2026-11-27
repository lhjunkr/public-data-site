package kr.hojun.policymatch.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 마이홈 수집기 (12/11). 실제 API 대신 정해진 응답을 돌려주는 가짜 클라이언트를 쓰고, DB 는 실제 MySQL 에 쓴 뒤 롤백한다.
 * 시험 공고 번호는 T-M- 로 시작한다. 로컬 DB 의 실제 마이홈 원본과 섞이지 않게 그 번호로만 센다.
 */
@SpringBootTest
@Transactional
class MyhomeCollectorTest {

    private static final int PER_PAGE = 2;
    private static final String RENTAL_URL = "https://example.test/rsdtRcritNtcList";
    private static final String SALE_URL = "https://example.test/ltRsdtRcritNtcList";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PolicyRawStore store;
    @Autowired CollectRunStore runs;

    @Test
    void 지역별로_나뉜_행을_공고_주택_하나로_묶어_원본에_넣는다() {
        // 12/5: 전세임대처럼 한 공고가 여러 시군구에 걸치면 같은 (pblancId, houseSn) 행이 지역마다 온다
        FakeClient fake = fake(
                List.of(row("T-M-1", 1, "서울특별시", "강남구"), row("T-M-1", 1, "서울특별시", "서초구"),
                        row("T-M-1", 1, "경기도", "성남시"), row("T-M-2", 1, "부산광역시", "")),
                List.of(row("T-M-3", 1, "세종특별자치시", "")));

        CollectResult result = collector(fake).collectAll();

        assertEquals(5, result.totCount(), "행 수(임대 4 + 분양 1)");
        assertEquals(5, result.itemsSeen());
        assertEquals(3, testRawCount(), "(공고, 주택) 3건");
        JsonNode t1 = objectMapper.readTree(raw("rent-T-M-1-1"));
        assertTrue(t1.isArray());
        assertEquals(3, t1.size(), "지역 행 3개를 모두 담는다");
        assertEquals(1, objectMapper.readTree(raw("sale-T-M-3-1")).size(), "분양은 sale- 로 시작한다");

        Map<String, Object> run = lastRun();
        assertEquals("myhome", run.get("source"));
        assertEquals("SUCCESS", run.get("status"));
        assertEquals(5, ((Number) run.get("tot_count")).intValue());
        assertEquals(5, ((Number) run.get("items_seen")).intValue());
    }

    @Test
    void 같은_주택의_지역_행이_페이지를_넘어도_한_건으로_묶인다() {
        // 2행씩: 1쪽 = T-M-4 강남 · 서초, 2쪽 = T-M-4 마포 · T-M-5. 페이지마다 바로 넣으면 앞 지역이 덮어써진다
        FakeClient fake = fake(
                List.of(row("T-M-4", 7, "서울특별시", "강남구"), row("T-M-4", 7, "서울특별시", "서초구"),
                        row("T-M-4", 7, "서울특별시", "마포구"), row("T-M-5", 1, "대구광역시", "")),
                List.of());

        CollectResult result = collector(fake).collectAll();

        assertEquals(3, result.pagesRead(), "임대 2쪽 + 분양 1쪽(0건이어도 첫 쪽은 부른다)");
        assertEquals(3, objectMapper.readTree(raw("rent-T-M-4-7")).size());
        assertEquals(2, testRawCount());
    }

    @Test
    void 두_번_돌려도_원본_행이_늘지_않는다() {
        List<Map<String, Object>> rental = List.of(row("T-M-6", 1, "서울특별시", "강남구"), row("T-M-7", 2, "경기도", ""));

        collector(fake(rental, List.of())).collectAll();
        collector(fake(rental, List.of())).collectAll();

        assertEquals(2, testRawCount(), "(source, policy_no) upsert");
    }

    @Test
    void 중간에_실패하면_FAILED로_남기고_원본은_하나도_넣지_않는다() {
        FakeClient fake = fake(
                List.of(row("T-M-8", 1, "서울특별시", ""), row("T-M-9", 1, "서울특별시", ""), row("T-M-10", 1, "서울특별시", "")),
                List.of());
        fake.failAt = "rent page=2";

        assertThrows(ExternalApiException.class, () -> collector(fake).collectAll());

        Map<String, Object> run = lastRun();
        assertEquals("FAILED", run.get("status"));
        assertEquals(PER_PAGE, ((Number) run.get("items_seen")).intValue(), "1쪽까지 받은 행 수를 남긴다");
        assertEquals(0, testRawCount(), "끝까지 받지 못했으면 묶을 수 없으므로 넣지 않는다");
    }

    @Test
    void 받은_행이_95퍼센트에_못_미치면_FAILED() {
        FakeClient fake = fake(List.of(row("T-M-11", 1, "서울특별시", ""), row("T-M-12", 1, "서울특별시", "")), List.of());
        fake.rentalTotalOverride = 10; // 10행이라더니 2행 뒤에 빈 쪽이 온다

        assertThrows(ExternalApiException.class, () -> collector(fake).collectAll());

        assertEquals("FAILED", lastRun().get("status"));
        assertEquals(0, testRawCount());
    }

    @Test
    void 공공임대가_0건이면_실패로_본다() {
        // 12/5: 347행. 0건을 성공으로 받으면 정규화가 임대 공고를 모두 끄려 한다
        assertThrows(ExternalApiException.class,
                () -> collector(fake(List.of(), List.of(row("T-M-13", 1, "서울특별시", "")))).collectAll());

        assertEquals("FAILED", lastRun().get("status"));
    }

    @Test
    void 건수가_상한을_넘으면_받기_전에_멈춘다() {
        FakeClient fake = fake(List.of(row("T-M-14", 1, "서울특별시", "")), List.of());
        fake.rentalTotalOverride = MyhomeCollector.MAX_PAGES * PER_PAGE + 1;

        ExternalApiException e = assertThrows(ExternalApiException.class, () -> collector(fake).collectAll());

        assertTrue(e.getMessage().contains("상한"), e.getMessage());
        assertEquals(1, fake.requests.size(), "첫 쪽만 부르고 멈춘다");
        assertEquals(0, testRawCount());
    }

    @Test
    void 요청은_쪽_번호와_행_수만_보내고_지역_조건은_넣지_않는다() {
        FakeClient fake = fake(List.of(row("T-M-15", 1, "서울특별시", "")), List.of(row("T-M-16", 1, "경기도", "")));

        collector(fake).collectAll();

        assertEquals(2, fake.requests.size(), "임대 1번 + 분양 1번");
        assertEquals(RENTAL_URL, fake.urls.get(0));
        assertEquals(SALE_URL, fake.urls.get(1));
        assertEquals(Map.of("pageNo", "1", "numOfRows", String.valueOf(PER_PAGE)), fake.requests.get(0));
    }

    @Test
    void 항목이_하나면_객체로_와도_읽는다() {
        JsonNode one = objectMapper.readTree("{\"response\":{\"body\":{\"item\":{\"pblancId\":\"A\",\"houseSn\":1},"
                + "\"totalCount\":1}}}");
        JsonNode none = objectMapper.readTree("{\"response\":{\"body\":{\"item\":\"\",\"totalCount\":0}}}");

        assertEquals(1, MyhomeCollector.parsePage(one, "t").rows().size());
        assertEquals(0, MyhomeCollector.parsePage(none, "t").rows().size());
    }

    @Test
    void totalCount가_없으면_결과_코드를_담아_실패한다() {
        JsonNode error = objectMapper.readTree(
                "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"SERVICE_KEY_IS_NOT_REGISTERED_ERROR\"}}}");

        ExternalApiException e = assertThrows(ExternalApiException.class, () -> MyhomeCollector.parsePage(error, "t"));

        assertTrue(e.getMessage().contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR"), e.getMessage());
    }

    @Test
    void 다른_출처의_마지막_성공_수집_시각을_바꾸지_않는다() {
        Optional<String> youthBefore = runs.lastSuccessStartedAt(Source.YOUTH);
        Optional<String> kstartupBefore = runs.lastSuccessStartedAt(Source.KSTARTUP);

        collector(fake(List.of(row("T-M-17", 1, "서울특별시", "")), List.of())).collectAll();

        assertEquals(youthBefore, runs.lastSuccessStartedAt(Source.YOUTH));
        assertEquals(kstartupBefore, runs.lastSuccessStartedAt(Source.KSTARTUP));
        assertTrue(runs.lastSuccessStartedAt(Source.MYHOME).isPresent());
    }

    // ---------- 도우미 ----------

    private MyhomeCollector collector(FakeClient fake) {
        MyhomeProperties props = new MyhomeProperties();
        props.setNumOfRows(PER_PAGE);
        props.setRentalUrl(RENTAL_URL);
        props.setSaleUrl(SALE_URL);
        return new MyhomeCollector(fake, store, runs, props);
    }

    /** 실제 응답에서 묶기 · 지역에 쓰는 칸만 채운 한 행. houseSn 은 실제처럼 숫자로 온다. */
    private static Map<String, Object> row(String pblancId, int houseSn, String brtc, String signgu) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pblancId", pblancId);
        r.put("houseSn", houseSn);
        r.put("pblancNm", "시험 모집공고 " + pblancId);
        r.put("brtcNm", brtc);
        r.put("signguNm", signgu);
        return r;
    }

    private FakeClient fake(List<Map<String, Object>> rental, List<Map<String, Object>> sale) {
        DataGoKrProperties props = new DataGoKrProperties();
        props.setKey("test");
        return new FakeClient(props, objectMapper, rental, sale);
    }

    private int testRawCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM policy_raw WHERE source = 'myhome' "
                + "AND (policy_no LIKE 'rent-T-M-%' OR policy_no LIKE 'sale-T-M-%')", Integer.class);
        return n == null ? 0 : n;
    }

    private String raw(String policyNo) {
        return jdbc.queryForObject("SELECT raw_json FROM policy_raw WHERE source = 'myhome' AND policy_no = ?",
                String.class, policyNo);
    }

    private Map<String, Object> lastRun() {
        return jdbc.queryForMap("SELECT source, status, tot_count, items_seen FROM collect_run ORDER BY id DESC LIMIT 1");
    }

    /** 실제 마이홈 응답 모양 {"response":{"header":…,"body":{"item":[…],"totalCount":n,…}}} 을 흉내 낸다. */
    static class FakeClient extends DataGoKrClient {

        final List<Map<String, String>> requests = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        private final ObjectMapper mapper;
        private final List<Map<String, Object>> rental;
        private final List<Map<String, Object>> sale;
        /** 이 위치(예: "rent page=2")에서 예외를 던진다 */
        String failAt;
        /** 임대 totalCount 를 실제 행 수와 다르게 알려 준다 */
        Integer rentalTotalOverride;

        FakeClient(DataGoKrProperties props, ObjectMapper mapper, List<Map<String, Object>> rental,
                   List<Map<String, Object>> sale) {
            super(props, mapper);
            this.mapper = mapper;
            this.rental = rental;
            this.sale = sale;
        }

        @Override
        public JsonNode getJson(Source source, String url, Map<String, String> params, String label) {
            requests.add(Map.copyOf(params));
            urls.add(url);
            if (label.equals(failAt)) {
                throw new ExternalApiException(source, "가짜 실패 (" + label + ")");
            }
            boolean isRental = url.equals(RENTAL_URL);
            List<Map<String, Object>> all = isRental ? rental : sale;
            int total = isRental && rentalTotalOverride != null ? rentalTotalOverride : all.size();
            int page = Integer.parseInt(params.get("pageNo"));
            int perPage = Integer.parseInt(params.get("numOfRows"));
            int from = Math.min(all.size(), (page - 1) * perPage);
            int to = Math.min(all.size(), from + perPage);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("item", all.subList(from, to));
            body.put("numOfRows", perPage);
            body.put("pageNo", page);
            body.put("totalCount", total);
            Map<String, Object> header = Map.of("resultCode", "00", "resultMsg", "NORMAL SERVICE.");
            return mapper.readTree(mapper.writeValueAsString(Map.of("response", Map.of("header", header, "body", body))));
        }
    }
}
