package kr.hojun.policymatch.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
 * 잡알리오 수집기 (12/13). 실제 API 대신 정해진 응답을 돌려주는 가짜 클라이언트를 쓰고, DB 는 실제 MySQL 에 쓴 뒤 롤백한다.
 */
@SpringBootTest
@Transactional
class AlioCollectorTest {

    private static final int PER_PAGE = 2;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PolicyRawStore store;
    @Autowired CollectRunStore runs;

    @Test
    void 진행_중_공고를_끝까지_받으면_SUCCESS와_출처_alio로_기록한다() {
        CollectResult result = collector(fake(5, 5, 0)).collectAll();

        assertEquals(5, result.totCount());
        assertEquals(5, result.itemsSeen());
        assertEquals(3, result.pagesRead(), "2건씩 5건 = 3페이지");
        Map<String, Object> run = lastRun();
        assertEquals("alio", run.get("source"));
        assertEquals("SUCCESS", run.get("status"));
        assertEquals(5, ((Number) run.get("tot_count")).intValue());
        assertEquals(5, testRows());
    }

    @Test
    void 요청에는_진행_중_조건과_JSON_형식만_넣고_지역_조건은_넣지_않는다() {
        // 조건 없이 부르면 마감된 공고까지 115,944건(12/5). 근무지는 여러 값이라 전부 받은 뒤 정규화에서 거른다
        FakeClient fake = fake(3, 3, 0);

        collector(fake).collectAll();

        assertEquals(2, fake.requests.size());
        Map<String, String> first = fake.requests.get(0);
        assertEquals("Y", first.get(AlioCollector.ONGOING_PARAM));
        assertEquals("json", first.get("resultType"));
        assertEquals("1", first.get("pageNo"));
        assertEquals(String.valueOf(PER_PAGE), first.get("numOfRows"));
        assertEquals("2", fake.requests.get(1).get("pageNo"));
        assertFalse(first.keySet().stream().anyMatch(name -> name.toLowerCase().contains("rgn")), "근무지 조건 없음");
    }

    @Test
    void 두_번_받아도_원본_행이_늘지_않는다() {
        // 유니크 키 (source, policy_no) 의 upsert. 12/5: 547건의 공고 번호가 모두 달랐다
        collector(fake(5, 5, 0)).collectAll();
        collector(fake(5, 5, 0)).collectAll();

        assertEquals(5, testRows());
    }

    @Test
    void 원본은_받은_그대로_넣는다() {
        // 담당자 연락처가 섞인 전형 안내 글도 원본에는 남는다. 화면으로 옮길 때 뺀다(AlioNormalizer)
        collector(fake(1, 1, 0)).collectAll();

        String raw = jdbc.queryForObject(
                "SELECT raw_json FROM policy_raw WHERE source = 'alio' AND policy_no = 'T-A-1'", String.class);
        assertTrue(raw.contains("scrnprcdrMthdExpln"), raw);
        assertTrue(raw.contains("경남,경기"), raw);
    }

    @Test
    void 중간에_실패하면_FAILED로_기록하고_예외를_던진다() {
        assertThrows(ExternalApiException.class, () -> collector(fake(5, 5, 2)).collectAll());

        Map<String, Object> run = lastRun();
        assertEquals("FAILED", run.get("status"));
        assertEquals(PER_PAGE, ((Number) run.get("items_seen")).intValue(), "1페이지분만 저장됨");
    }

    @Test
    void 받은_건수가_95퍼센트에_못_미치면_FAILED() {
        // API 는 진행 중 10건이라고 했지만 5건 뒤에 빈 페이지가 온다
        assertThrows(ExternalApiException.class, () -> collector(fake(10, 5, 0)).collectAll());

        assertEquals("FAILED", lastRun().get("status"));
    }

    @Test
    void 진행_중_공고가_0건이면_실패로_본다() {
        // 공공기관 약 370곳에 진행 중 채용이 0건일 수는 없다. 성공으로 받으면 정규화가 공고를 모두 끄려 한다
        ExternalApiException e = assertThrows(ExternalApiException.class, () -> collector(fake(0, 0, 0)).collectAll());

        assertTrue(e.getMessage().contains("0건"), e.getMessage());
        assertEquals("FAILED", lastRun().get("status"));
    }

    @Test
    void 조건이_무시된_것처럼_건수가_상한을_넘으면_받기_전에_멈춘다() {
        // 진행 중 조건이 안 걸리면 totalCount 가 115,944건이 된다. 그대로 받으면 천 번 넘게 호출한다
        int tooMany = AlioCollector.MAX_PAGES * PER_PAGE + 1;
        FakeClient fake = fake(tooMany, tooMany, 0);

        ExternalApiException e = assertThrows(ExternalApiException.class, () -> collector(fake).collectAll());

        assertTrue(e.getMessage().contains("ongoingYn"), e.getMessage());
        assertEquals(1, fake.requests.size(), "첫 페이지만 부르고 멈춘다");
        assertEquals("FAILED", lastRun().get("status"));
        assertEquals(0, testRows());
    }

    @Test
    void resultCode가_200이_아니면_결과_코드를_담아_실패한다() {
        // 인증 · 한도 오류를 "0건"으로 오해하지 않게
        FakeClient fake = fake(5, 5, 0);
        fake.resultCode = 401;

        ExternalApiException e = assertThrows(ExternalApiException.class, () -> collector(fake).collectAll());

        assertTrue(e.getMessage().contains("resultCode=401"), e.getMessage());
        assertEquals("FAILED", lastRun().get("status"));
        assertEquals(0, testRows());
    }

    @Test
    void 온통청년의_마지막_성공_수집_시각을_바꾸지_않는다() {
        // 화면의 "정책 정보 기준 시각"과 온통청년 정규화 기준은 youth 의 마지막 성공 수집이다(D-029)
        Optional<String> youthBefore = runs.lastSuccessStartedAt(Source.YOUTH);

        collector(fake(5, 5, 0)).collectAll();

        assertEquals(youthBefore, runs.lastSuccessStartedAt(Source.YOUTH));
        assertTrue(runs.lastSuccessStartedAt(Source.ALIO).isPresent());
    }

    // ---------- 도우미 ----------

    private AlioCollector collector(FakeClient fake) {
        AlioProperties props = new AlioProperties();
        props.setNumOfRows(PER_PAGE);
        return new AlioCollector(fake, store, runs, props);
    }

    /**
     * @param totalCount  API 가 알려주는 진행 중 건수
     * @param actualItems 실제로 돌려줄 건수. 이보다 뒤 페이지는 빈 목록이다
     * @param failAtPage  이 페이지에서 예외를 던진다. 0 이면 실패하지 않는다
     */
    private FakeClient fake(int totalCount, int actualItems, int failAtPage) {
        DataGoKrProperties props = new DataGoKrProperties();
        props.setKey("test");
        return new FakeClient(props, objectMapper, totalCount, actualItems, failAtPage);
    }

    private Map<String, Object> lastRun() {
        return jdbc.queryForMap("SELECT source, status, tot_count, items_seen FROM collect_run ORDER BY id DESC LIMIT 1");
    }

    private int testRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_raw WHERE source = 'alio' AND policy_no LIKE 'T-A-%'", Integer.class);
        return n == null ? 0 : n;
    }

    /** 실제 잡알리오 응답 모양(resultCode · resultMsg · totalCount · result)을 흉내 낸다 (12/5). */
    static class FakeClient extends DataGoKrClient {

        final List<Map<String, String>> requests = new ArrayList<>();
        int resultCode = 200;
        private final ObjectMapper mapper;
        private final int totalCount;
        private final int actualItems;
        private final int failAtPage;

        FakeClient(DataGoKrProperties props, ObjectMapper mapper, int totalCount, int actualItems, int failAtPage) {
            super(props, mapper);
            this.mapper = mapper;
            this.totalCount = totalCount;
            this.actualItems = actualItems;
            this.failAtPage = failAtPage;
        }

        @Override
        public JsonNode getJson(Source source, String url, Map<String, String> params, String label) {
            requests.add(Map.copyOf(params));
            int page = Integer.parseInt(params.get("pageNo"));
            int perPage = Integer.parseInt(params.get("numOfRows"));
            if (page == failAtPage) {
                throw new ExternalApiException(source, "가짜 실패 (" + label + ")");
            }
            if (resultCode != 200) {
                return mapper.readTree("{\"resultCode\":" + resultCode + ",\"resultMsg\":\"인증 실패\"}");
            }
            List<String> items = new ArrayList<>();
            int from = (page - 1) * perPage + 1;
            for (int i = from; i < from + perPage && i <= actualItems; i++) {
                items.add("{\"recrutPblntSn\":\"T-A-" + i + "\",\"recrutPbancTtl\":\"시험 채용 " + i + "\","
                        + "\"hireTypeNmLst\":\"정규직\",\"workRgnNmLst\":\"경남,경기\",\"ongoingYn\":\"Y\","
                        + "\"scrnprcdrMthdExpln\":\"문의: 담당자\"}");
            }
            String json = "{\"resultCode\":200,\"resultMsg\":\"성공했습니다.\",\"totalCount\":" + totalCount
                    + ",\"result\":[" + String.join(",", items) + "]}";
            return mapper.readTree(json);
        }
    }
}
