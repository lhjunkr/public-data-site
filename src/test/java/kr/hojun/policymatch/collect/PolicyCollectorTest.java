package kr.hojun.policymatch.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 실행 기록 테스트 (D-017). 실제 API 대신 정해진 응답을 돌려주는 가짜 클라이언트를 쓴다.
 * @Transactional: policy_raw · collect_run 에 넣은 행은 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class PolicyCollectorTest {

    private static final int PAGE_SIZE = 2;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PolicyRawStore store;
    @Autowired CollectRunStore runs;

    @Test
    void 끝까지_수집하면_SUCCESS로_기록한다() {
        CollectResult result = collector(5, 5, 0).collectAll();

        assertEquals(5, result.itemsSeen());
        Map<String, Object> run = lastRun();
        assertEquals("SUCCESS", run.get("status"));
        assertEquals(5, ((Number) run.get("items_seen")).intValue());
    }

    @Test
    void 중간에_실패하면_FAILED로_기록하고_예외를_던진다() {
        // 10/30 사고: 9페이지에서 실패. 여기서는 2페이지에서 실패한다
        assertThrows(YouthApiException.class, () -> collector(5, 5, 2).collectAll());

        Map<String, Object> run = lastRun();
        assertEquals("FAILED", run.get("status"));
        assertEquals(PAGE_SIZE, ((Number) run.get("items_seen")).intValue(), "1페이지분만 저장됨");
    }

    @Test
    void 받은_건수가_전체의_95퍼센트에_못_미치면_FAILED() {
        // API 는 10건이라고 했지만 5건 뒤에 빈 페이지가 온다
        assertThrows(YouthApiException.class, () -> collector(10, 5, 0).collectAll());

        assertEquals("FAILED", lastRun().get("status"));
    }

    // ---------- 도우미 ----------

    /**
     * @param reportedTotal API 가 알려주는 전체 건수(totCount)
     * @param actualItems   실제로 돌려줄 건수. 이보다 뒤 페이지는 빈 목록이다
     * @param failAtPage    이 페이지에서 예외를 던진다. 0 이면 실패하지 않는다
     */
    private PolicyCollector collector(int reportedTotal, int actualItems, int failAtPage) {
        YouthApiProperties props = new YouthApiProperties();
        props.setPageSize(PAGE_SIZE);
        props.setKey("test");
        YouthApiClient fake = new FakeClient(props, objectMapper, reportedTotal, actualItems, failAtPage);
        return new PolicyCollector(fake, store, runs, props);
    }

    private Map<String, Object> lastRun() {
        return jdbc.queryForMap("SELECT status, items_seen FROM collect_run ORDER BY id DESC LIMIT 1");
    }

    static class FakeClient extends YouthApiClient {

        private final ObjectMapper mapper;
        private final int pageSize;
        private final int reportedTotal;
        private final int actualItems;
        private final int failAtPage;

        FakeClient(YouthApiProperties props, ObjectMapper mapper, int reportedTotal, int actualItems, int failAtPage) {
            super(props, mapper);
            this.mapper = mapper;
            this.pageSize = props.getPageSize();
            this.reportedTotal = reportedTotal;
            this.actualItems = actualItems;
            this.failAtPage = failAtPage;
        }

        @Override
        public JsonNode fetchPage(int pageNum) {
            if (pageNum == failAtPage) {
                throw new YouthApiException("가짜 실패 (page=" + pageNum + ")");
            }
            List<String> items = new ArrayList<>();
            int from = (pageNum - 1) * pageSize + 1;
            for (int i = from; i < from + pageSize && i <= actualItems; i++) {
                items.add("{\"plcyNo\":\"T-C-" + i + "\",\"plcyNm\":\"시험 " + i + "\"}");
            }
            String json = "{\"result\":{\"pagging\":{\"totCount\":" + reportedTotal + "},"
                    + "\"youthPolicyList\":[" + String.join(",", items) + "]}}";
            return mapper.readTree(json);
        }
    }
}
