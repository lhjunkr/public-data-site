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
 * 잡알리오 공공기관 채용공고 중 진행 중인 것을 전 페이지 받아 policy_raw 에 source = alio 로 적재한다 (12/13).
 *
 * 설계 메모 (근거: docs/sources/alio.md, 12/5 측정)
 *  - 요청 조건은 "진행 중"(ongoingYn = Y) 하나뿐이다. 조건 없이 부르면 마감된 과거 공고까지 115,944건이 온다.
 *    지역 · 고용형태는 요청에서 거르지 않는다. 여러 값(콤마)이라 나눠 부르면 겹치고, 정규화에서 연결 표로 거른다.
 *  - 응답 한 건 = 공고 한 건이다(12/5: 547건, recrutPblntSn 547개 모두 다름). 마이홈처럼 묶을 필요가 없어
 *    K-Startup 처럼 페이지마다 넣는다. 같은 번호는 유니크 키 (source, policy_no) 의 upsert 라 두 번 받아도 행이 늘지 않는다.
 *  - 성공 판단: totalCount 의 95% 이상을 받아야 SUCCESS 다. 진행 중 조건이 걸린 응답의 totalCount 는 "받아야 할 건수"라
 *    K-Startup 의 matchCount 와 같은 뜻이다. totalCount 가 0 이면 실패로 본다 — 공공기관 약 370곳에 진행 중 채용이
 *    하나도 없을 수는 없고, 그대로 성공 처리하면 정규화가 공고를 모두 끄려 한다(마이홈 공공임대 0건 규칙과 같음).
 *  - 건수가 상한(30페이지 = 3,000건, 12/5 의 약 5.5배)을 넘으면 받기 전에 멈춘다. 진행 중 조건이 무시되면
 *    115,944건을 1,160번 불러 일일 한도를 쓰기 때문이다.
 *  - 응답의 resultCode 가 200 이 아니면 결과 코드 · 메시지를 담아 실패한다. 인증 오류를 "0건"으로 오해하지 않게.
 *  - 원본은 받은 그대로 둔다. 담당자 이메일 · 전화가 섞인 전형 안내 글(scrnprcdrMthdExpln)도 원본에는 남고,
 *    검색용 표로 옮길 때 뺀다(AlioNormalizer). 원본 표는 화면 · API 로 나가지 않는다.
 */
@Service
public class AlioCollector implements SourceCollector {

    private static final Logger log = LoggerFactory.getLogger(AlioCollector.class);
    private static final Source SOURCE = Source.ALIO;
    private static final long PAGE_DELAY_MS = 200; // 상대 서버 배려

    /** 받을 최대 페이지. 100건씩이면 3,000건 = 12/5 진행 중 547건의 약 5.5배. 넘으면 조건이 안 걸린 것으로 본다. */
    static final int MAX_PAGES = 30;

    /** "진행 중" 조건. 빼면 마감된 공고까지 온다(12/5: 115,944건). */
    static final String ONGOING_PARAM = "ongoingYn";

    /** 성공 응답의 resultCode (12/5 실측: 숫자 200, resultMsg "성공했습니다.") */
    static final int SUCCESS_CODE = 200;

    private final DataGoKrClient client;
    private final PolicyRawStore store;
    private final CollectRunStore runs;
    private final AlioProperties props;

    public AlioCollector(DataGoKrClient client, PolicyRawStore store, CollectRunStore runs, AlioProperties props) {
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
        int perPage = Math.max(1, props.getNumOfRows());

        int totalCount = 0;
        int itemsSeen = 0;
        int pagesRead = 0;
        try {
            JsonNode first = fetchPage(1, perPage);
            totalCount = totalCountOf(first, "page=1");
            int totalPages = (totalCount + perPage - 1) / perPage;

            if (totalPages > MAX_PAGES) {
                throw new ExternalApiException(SOURCE, "진행 중 채용공고가 " + totalCount + "건으로 상한 " + MAX_PAGES * perPage
                        + "건을 넘습니다. 진행 중 조건(ongoingYn=Y)이 적용되지 않았을 수 있어 받지 않습니다.");
            }
            log.info("잡알리오 수집 시작 — run={} totalCount={} perPage={} totalPages={}", runId, totalCount, perPage, totalPages);

            itemsSeen = savePage(first, startedAt, 1);
            pagesRead = 1;

            for (int page = 2; page <= totalPages; page++) {
                sleepQuietly();
                JsonNode next = fetchPage(page, perPage);
                totalCountOf(next, "page=" + page); // 중간 페이지의 오류 응답도 결과 코드로 알아챈다
                int saved = savePage(next, startedAt, page);
                pagesRead++;
                itemsSeen += saved;

                if (saved == 0) {
                    log.warn("잡알리오 page={} 가 비어 있어 수집을 조기 종료합니다.", page);
                    break;
                }
            }
        } catch (RuntimeException e) {
            runs.finish(runId, CollectRunStore.FAILED, totalCount, itemsSeen, e.getMessage());
            throw e;
        }

        if (totalCount == 0 || itemsSeen < totalCount * MIN_COMPLETE_RATIO) {
            String msg = "잡알리오 수집이 끝까지 되지 않았습니다 — totalCount=" + totalCount + ", itemsSeen=" + itemsSeen
                    + (totalCount == 0 ? " (진행 중 공고 0건은 서버 쪽 문제로 봅니다)" : "");
            runs.finish(runId, CollectRunStore.FAILED, totalCount, itemsSeen, msg);
            throw new ExternalApiException(SOURCE, msg);
        }
        runs.finish(runId, CollectRunStore.SUCCESS, totalCount, itemsSeen, null);

        long rows = store.count(SOURCE);
        log.info("잡알리오 수집 완료 — run={} pagesRead={} itemsSeen={} policy_raw rows={}", runId, pagesRead, itemsSeen, rows);
        return new CollectResult(totalCount, pagesRead, itemsSeen, rows);
    }

    private JsonNode fetchPage(int page, int perPage) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pageNo", String.valueOf(page));
        params.put("numOfRows", String.valueOf(perPage));
        params.put("resultType", "json");
        params.put(ONGOING_PARAM, "Y");
        return client.getJson(SOURCE, props.getBaseUrl(), params, "page=" + page);
    }

    /**
     * 응답 모양: {"resultCode":200,"resultMsg":"성공했습니다.","totalCount":547,"result":[…]} (12/5).
     * resultCode 가 200 이 아니거나 totalCount 가 없으면 결과 코드 · 메시지를 담아 실패한다.
     */
    static int totalCountOf(JsonNode root, String label) {
        int resultCode = JsonNodes.intValue(root.path("resultCode"), -1);
        int totalCount = JsonNodes.intValue(root.path("totalCount"), -1);
        if (resultCode != SUCCESS_CODE || totalCount < 0) {
            String head = root.toString();
            throw new ExternalApiException(SOURCE, "잡알리오 응답이 성공이 아닙니다 (" + label + "). resultCode="
                    + JsonNodes.text(root.path("resultCode")) + ", resultMsg=" + JsonNodes.text(root.path("resultMsg"))
                    + ". 앞부분: " + DataGoKrClient.maskKey(head.length() <= 200 ? head : head.substring(0, 200) + "…"));
        }
        return totalCount;
    }

    /** 한 페이지의 공고를 넣고 넣은 건수를 돌려준다. 공공데이터포털 응답은 항목이 하나면 객체로 올 수 있어 둘 다 읽는다. */
    private int savePage(JsonNode root, LocalDateTime fetchedAt, int page) {
        JsonNode list = root.path("result");
        List<JsonNode> rows = new ArrayList<>();
        if (list.isArray()) {
            for (int i = 0; i < list.size(); i++) {
                rows.add(list.get(i));
            }
        } else if (list.isObject()) {
            rows.add(list);
        } else if (!list.isMissingNode() && !list.isNull()) {
            throw new ExternalApiException(SOURCE, "응답의 result 가 목록이 아닙니다 (page=" + page + ").");
        }

        List<PolicyRawStore.RawItem> items = new ArrayList<>(rows.size());
        for (JsonNode item : rows) {
            String policyNo = JsonNodes.text(item.path("recrutPblntSn"));
            if (policyNo == null || policyNo.isBlank()) {
                log.warn("잡알리오 recrutPblntSn 이 없는 항목을 건너뜁니다 (page={}).", page);
                continue;
            }
            items.add(new PolicyRawStore.RawItem(policyNo.strip(), item.toString()));
        }
        return items.isEmpty() ? 0 : store.upsertAll(SOURCE, items, fetchedAt);
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
