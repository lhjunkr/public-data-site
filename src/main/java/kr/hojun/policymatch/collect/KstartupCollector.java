package kr.hojun.policymatch.collect;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * K-Startup 지원사업 공고 중 모집 중인 것을 전 페이지 받아 policy_raw 에 source = kstartup 으로 적재한다 (12/9).
 *
 * 설계 메모
 *  - 요청 조건은 "모집 중"(rcrt_prgs_yn = Y) 하나뿐이다. 지역 조건(supt_regin)은 일부러 넣지 않는다.
 *    공고의 59% 가 지역 "전국"이고(12/5), 화면 검색은 시도별로 하므로 전부 받은 뒤 정규화에서 policy_sido 로 거른다.
 *    시도마다 따로 부르면 17번 호출에 "전국" 공고가 매번 중복되거나 빠진다.
 *  - 성공 판단은 totalCount 가 아니라 matchCount 로 한다. totalCount 는 지난 공고까지 합친 30,656건이고,
 *    조건에 맞는 건수는 matchCount(175건)다. totalCount 로 판단하면 매번 0.6% 만 받은 실패가 된다.
 *  - 조건이 무시된 것처럼 건수가 상한을 넘으면 받기 전에 멈춘다. 모집 중 175건이 3만 건이 됐다면
 *    파라미터 이름이 바뀌었을 가능성이 크고, 그대로 받으면 300번 넘게 호출해 일일 한도를 쓴다.
 *  - 통합공고 사업(getBusinessInformation02, 511건)은 받지 않는다. 날짜 · 지역 · 나이가 없는 연간 사업 소개라
 *    "지금 신청할 수 있는 것"이 아니다(docs/sources/kstartup.md).
 *  - 원본은 받은 그대로 둔다. HTML 엔티티(&amp;) 풀기 · 나이 구간 변환은 12/10 정규화에서 한다.
 */
@Service
public class KstartupCollector implements SourceCollector {

    private static final Logger log = LoggerFactory.getLogger(KstartupCollector.class);
    private static final Source SOURCE = Source.KSTARTUP;
    private static final long PAGE_DELAY_MS = 200; // 상대 서버 배려

    /** 받을 최대 페이지. 100건씩이면 3,000건 = 실측(175건)의 약 17배. 넘으면 조건이 안 걸린 것으로 본다. */
    static final int MAX_PAGES = 30;

    /** "모집 중" 조건. 이름에 대괄호 · 콜론이 들어가며, 인코딩은 DataGoKrClient 가 한다. */
    static final String RECRUITING_PARAM = "cond[rcrt_prgs_yn::EQ]";

    private final DataGoKrClient client;
    private final PolicyRawStore store;
    private final CollectRunStore runs;
    private final KstartupProperties props;

    public KstartupCollector(DataGoKrClient client, PolicyRawStore store, CollectRunStore runs,
                             KstartupProperties props) {
        this.client = client;
        this.store = store;
        this.runs = runs;
        this.props = props;
    }

    @Override
    public Source source() {
        return SOURCE;
    }

    @Override
    public CollectResult collectAll() {
        // 초 단위로 자른다. collect_run.started_at 과 policy_raw.fetched_at 이 정확히 같은 값이어야 비교가 맞는다
        LocalDateTime startedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        long runId = runs.start(SOURCE, startedAt);
        int perPage = Math.max(1, props.getPerPage());

        int matchCount = 0;
        int itemsSeen = 0;
        int pagesRead = 0;
        try {
            JsonNode first = fetchPage(1, perPage);
            matchCount = JsonNodes.intValue(first.path("matchCount"), 0);
            int totalCount = JsonNodes.intValue(first.path("totalCount"), 0);
            int totalPages = (matchCount + perPage - 1) / perPage;

            if (totalPages > MAX_PAGES) {
                throw new ExternalApiException(SOURCE, "모집 중 공고가 " + matchCount + "건(전체 " + totalCount
                        + "건)으로 상한 " + MAX_PAGES * perPage + "건을 넘습니다. 모집 중 조건이 적용되지 않았을 수 있어 받지 않습니다.");
            }
            log.info("K-Startup 수집 시작 — run={} matchCount={} totalCount={} perPage={} totalPages={}",
                    runId, matchCount, totalCount, perPage, totalPages);

            itemsSeen = savePage(first, startedAt, 1);
            pagesRead = 1;

            for (int page = 2; page <= totalPages; page++) {
                sleepQuietly();
                int saved = savePage(fetchPage(page, perPage), startedAt, page);
                pagesRead++;
                itemsSeen += saved;

                if (saved == 0) {
                    log.warn("K-Startup page={} 가 비어 있어 수집을 조기 종료합니다.", page);
                    break;
                }
            }
        } catch (RuntimeException e) {
            runs.finish(runId, CollectRunStore.FAILED, matchCount, itemsSeen, e.getMessage());
            throw e;
        }

        if (matchCount == 0 || itemsSeen < matchCount * MIN_COMPLETE_RATIO) {
            String msg = "K-Startup 수집이 끝까지 되지 않았습니다 — matchCount=" + matchCount + ", itemsSeen=" + itemsSeen;
            runs.finish(runId, CollectRunStore.FAILED, matchCount, itemsSeen, msg);
            throw new ExternalApiException(SOURCE, msg);
        }
        // tot_count 칸에는 "이번 조건에서 받아야 할 건수"인 matchCount 를 적는다. 성공 판단의 기준이 그 값이기 때문이다
        runs.finish(runId, CollectRunStore.SUCCESS, matchCount, itemsSeen, null);

        long rows = store.count(SOURCE);
        log.info("K-Startup 수집 완료 — run={} pagesRead={} itemsSeen={} policy_raw rows={}", runId, pagesRead, itemsSeen, rows);
        return new CollectResult(matchCount, pagesRead, itemsSeen, rows);
    }

    private JsonNode fetchPage(int page, int perPage) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", String.valueOf(page));
        params.put("perPage", String.valueOf(perPage));
        params.put("returnType", "json");
        params.put(RECRUITING_PARAM, "Y");
        return client.getJson(SOURCE, props.getBaseUrl(), params, "page=" + page);
    }

    private int savePage(JsonNode root, LocalDateTime fetchedAt, int page) {
        JsonNode list = root.path("data");
        if (!list.isArray()) {
            String head = root.toString();
            throw new ExternalApiException(SOURCE, "응답에 data 배열이 없습니다 (page=" + page + "). 앞부분: "
                    + DataGoKrClient.maskKey(head.length() <= 200 ? head : head.substring(0, 200) + "…"));
        }

        List<PolicyRawStore.RawItem> items = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            JsonNode item = list.get(i);
            String policyNo = JsonNodes.text(item.path("pbanc_sn"));
            if (policyNo == null || policyNo.isBlank()) {
                log.warn("K-Startup pbanc_sn 이 없는 항목을 건너뜁니다 (page={}).", page);
                continue;
            }
            items.add(new PolicyRawStore.RawItem(policyNo, item.toString()));
        }
        return store.upsertAll(SOURCE, items, fetchedAt);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(PAGE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalApiException(SOURCE, "수집이 중단되었습니다.");
        }
    }
}
