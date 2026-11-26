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
 * K-Startup 수집기 (12/9). 실제 API 대신 정해진 응답을 돌려주는 가짜 클라이언트를 쓰고, DB 는 실제 MySQL 에 쓴 뒤 롤백한다.
 */
@SpringBootTest
@Transactional
class KstartupCollectorTest {

    private static final int PER_PAGE = 2;
    /** 실제 응답처럼 지난 공고까지 합친 전체 건수는 늘 크다(12/5: 30,656) */
    private static final int TOTAL_COUNT = 30656;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PolicyRawStore store;
    @Autowired CollectRunStore runs;

    @Test
    void 모집_중_공고를_끝까지_받으면_SUCCESS와_출처_kstartup으로_기록한다() {
        FakeClient fake = fake(5, 5, 0);

        CollectResult result = collector(fake).collectAll();

        assertEquals(5, result.itemsSeen());
        assertEquals(3, result.pagesRead(), "2건씩 5건 = 3페이지");
        Map<String, Object> run = lastRun();
        assertEquals("kstartup", run.get("source"));
        assertEquals("SUCCESS", run.get("status"));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_raw WHERE source = 'kstartup' AND policy_no LIKE 'T-K-%'", Integer.class));
    }

    @Test
    void 성공_판단과_기록은_전체_공고_수가_아니라_조건에_맞는_수로_한다() {
        // totalCount(30,656)로 판단하면 모집 중 5건을 다 받아도 0.02% 라 매번 실패한다
        collector(fake(5, 5, 0)).collectAll();

        Map<String, Object> run = lastRun();
        assertEquals("SUCCESS", run.get("status"));
        assertEquals(5, ((Number) run.get("tot_count")).intValue(), "matchCount 를 적는다");
    }

    @Test
    void 요청에는_모집_중_조건만_넣고_지역_조건은_넣지_않는다() {
        // 지역은 전부 받은 뒤 정규화에서 policy_sido 로 거른다. "전국" 공고가 59% 라 시도별로 부르면 빠지거나 겹친다
        FakeClient fake = fake(3, 3, 0);

        collector(fake).collectAll();

        assertEquals(2, fake.requests.size());
        Map<String, String> first = fake.requests.get(0);
        assertEquals("Y", first.get(KstartupCollector.RECRUITING_PARAM));
        assertEquals("json", first.get("returnType"));
        assertEquals("1", first.get("page"));
        assertEquals("2", fake.requests.get(1).get("page"));
        assertFalse(first.keySet().stream().anyMatch(name -> name.contains("supt_regin")), "지역 조건 없음");
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
        // API 는 모집 중 10건이라고 했지만 5건 뒤에 빈 페이지가 온다
        assertThrows(ExternalApiException.class, () -> collector(fake(10, 5, 0)).collectAll());

        assertEquals("FAILED", lastRun().get("status"));
    }

    @Test
    void 조건이_무시된_것처럼_건수가_상한을_넘으면_받기_전에_멈춘다() {
        // 모집 중 조건이 안 걸리면 matchCount 가 전체 건수가 된다. 그대로 받으면 수천 번 호출한다
        int tooMany = KstartupCollector.MAX_PAGES * PER_PAGE + 1;
        FakeClient fake = fake(tooMany, tooMany, 0);

        ExternalApiException e = assertThrows(ExternalApiException.class, () -> collector(fake).collectAll());

        assertTrue(e.getMessage().contains("모집 중 조건"), e.getMessage());
        assertEquals(1, fake.requests.size(), "첫 페이지만 부르고 멈춘다");
        assertEquals("FAILED", lastRun().get("status"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_raw WHERE source = 'kstartup' AND policy_no LIKE 'T-K-%'", Integer.class));
    }

    @Test
    void 온통청년의_마지막_성공_수집_시각을_바꾸지_않는다() {
        // 화면의 "정책 정보 기준 시각"과 온통청년 정규화 기준은 youth 의 마지막 성공 수집이다(D-029)
        Optional<String> youthBefore = runs.lastSuccessStartedAt(Source.YOUTH);

        collector(fake(5, 5, 0)).collectAll();

        assertEquals(youthBefore, runs.lastSuccessStartedAt(Source.YOUTH));
        assertTrue(runs.lastSuccessStartedAt(Source.KSTARTUP).isPresent());
    }

    // ---------- 도우미 ----------

    private KstartupCollector collector(FakeClient fake) {
        KstartupProperties props = new KstartupProperties();
        props.setPerPage(PER_PAGE);
        return new KstartupCollector(fake, store, runs, props);
    }

    /**
     * @param matchCount  API 가 알려주는 모집 중 건수
     * @param actualItems 실제로 돌려줄 건수. 이보다 뒤 페이지는 빈 목록이다
     * @param failAtPage  이 페이지에서 예외를 던진다. 0 이면 실패하지 않는다
     */
    private FakeClient fake(int matchCount, int actualItems, int failAtPage) {
        DataGoKrProperties props = new DataGoKrProperties();
        props.setKey("test");
        return new FakeClient(props, objectMapper, matchCount, actualItems, failAtPage);
    }

    private Map<String, Object> lastRun() {
        return jdbc.queryForMap("SELECT source, status, tot_count, items_seen FROM collect_run ORDER BY id DESC LIMIT 1");
    }

    /** 실제 K-Startup 응답 모양(currentCount · data · matchCount · page · perPage · totalCount)을 흉내 낸다. */
    static class FakeClient extends DataGoKrClient {

        final List<Map<String, String>> requests = new ArrayList<>();
        private final ObjectMapper mapper;
        private final int matchCount;
        private final int actualItems;
        private final int failAtPage;

        FakeClient(DataGoKrProperties props, ObjectMapper mapper, int matchCount, int actualItems, int failAtPage) {
            super(props, mapper);
            this.mapper = mapper;
            this.matchCount = matchCount;
            this.actualItems = actualItems;
            this.failAtPage = failAtPage;
        }

        @Override
        public JsonNode getJson(Source source, String url, Map<String, String> params, String label) {
            requests.add(Map.copyOf(params));
            int page = Integer.parseInt(params.get("page"));
            int perPage = Integer.parseInt(params.get("perPage"));
            if (page == failAtPage) {
                throw new ExternalApiException(source, "가짜 실패 (" + label + ")");
            }
            List<String> items = new ArrayList<>();
            int from = (page - 1) * perPage + 1;
            for (int i = from; i < from + perPage && i <= actualItems; i++) {
                items.add("{\"pbanc_sn\":\"T-K-" + i + "\",\"biz_pbanc_nm\":\"시험 공고 " + i + "\","
                        + "\"rcrt_prgs_yn\":\"Y\",\"supt_regin\":\"전국\"}");
            }
            String json = "{\"currentCount\":" + items.size() + ",\"data\":[" + String.join(",", items) + "],"
                    + "\"matchCount\":" + matchCount + ",\"page\":" + page + ",\"perPage\":" + perPage
                    + ",\"totalCount\":" + TOTAL_COUNT + "}";
            return mapper.readTree(json);
        }
    }
}
