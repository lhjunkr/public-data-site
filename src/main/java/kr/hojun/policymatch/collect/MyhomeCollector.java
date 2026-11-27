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
 * 마이홈 공공주택 모집공고(공공임대 · 공공분양)를 전 페이지 받아 policy_raw 에 source = myhome 으로 적재한다 (12/11).
 *
 * 설계 메모 (근거: docs/sources/myhome.md, 12/5 측정)
 *  - 응답 한 행은 공고가 아니라 "공고 + 주택 + 지역"이다. 전세임대 · 매입임대처럼 한 공고가 여러 시군구에 걸치면
 *    같은 (pblancId, houseSn) 행이 지역마다 따로 온다(최대 16행). 서로 다른 칸은 시도 · 시군구 · 공급 호수뿐이다.
 *    그래서 policy_raw 한 행 = (공고, 주택) 하나로 묶고, raw_json 에는 그 지역 행들을 받은 그대로 배열로 담는다.
 *    행마다 따로 넣으면 유니크 키 (source, policy_no) 때문에 지역 행이 서로 덮어써 지역이 사라진다.
 *  - 같은 (공고, 주택)의 지역 행이 페이지 경계에 걸칠 수 있으므로, 전 페이지를 다 받은 뒤에 묶어서 한 번에 넣는다.
 *    끝까지 받지 못했으면(실패 · 95% 미만) 아무것도 넣지 않는다.
 *  - 요청 조건은 없다. 서버가 접수가 끝나지 않은 공고만 준다(12/5: 마감 지남 0건). 지역도 요청에서 거르지 않는다.
 *  - policy_no 는 rent-{pblancId}-{houseSn} · sale-{pblancId}-{houseSn} 이다. 임대 · 분양 번호가 같은 체계라는
 *    보장이 없어 접두어로 나눈다(D-029 ①).
 *  - 성공 판단: 두 기능의 totalCount(행 수) 합의 95% 이상을 받아야 SUCCESS 다. collect_run.tot_count · items_seen 은
 *    행 수, policy_raw 행 수는 (공고, 주택) 수라 둘이 다르다(12/5: 418행 → 326건).
 *  - 건수가 상한(30페이지)을 넘으면 받기 전에 멈춘다. 12/5 측정의 약 9배다. 기능별 일일 한도가 1,000번이라
 *    엉뚱하게 많이 부르지 않게 한다.
 */
@Service
public class MyhomeCollector implements SourceCollector {

    private static final Logger log = LoggerFactory.getLogger(MyhomeCollector.class);
    private static final Source SOURCE = Source.MYHOME;
    private static final long PAGE_DELAY_MS = 200; // 상대 서버 배려

    /** 기능별로 받을 최대 페이지. 100행씩이면 3,000행 = 12/5 공공임대(347행)의 약 9배 */
    static final int MAX_PAGES = 30;

    /** 임대 · 분양 구분. policy_no 앞에 붙는다. */
    public enum Kind {
        RENT("rent", "공공임대"),
        SALE("sale", "공공분양");

        private final String prefix;
        private final String label;

        Kind(String prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        public String prefix() { return prefix; }
        public String label() { return label; }

        /** policy_no 의 접두어로 구분을 찾는다. 모르는 접두어면 null. */
        public static Kind ofPolicyNo(String policyNo) {
            for (Kind k : values()) {
                if (policyNo != null && policyNo.startsWith(k.prefix + "-")) {
                    return k;
                }
            }
            return null;
        }
    }

    private final DataGoKrClient client;
    private final PolicyRawStore store;
    private final CollectRunStore runs;
    private final MyhomeProperties props;

    public MyhomeCollector(DataGoKrClient client, PolicyRawStore store, CollectRunStore runs, MyhomeProperties props) {
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

        Fetched rental = new Fetched();
        Fetched sale = new Fetched();
        try {
            fetchAll(Kind.RENT, props.getRentalUrl(), perPage, rental);
            fetchAll(Kind.SALE, props.getSaleUrl(), perPage, sale);
        } catch (RuntimeException e) {
            runs.finish(runId, CollectRunStore.FAILED, rental.totalCount + sale.totalCount,
                    rental.rows.size() + sale.rows.size(), e.getMessage());
            throw e;
        }

        int totalCount = rental.totalCount + sale.totalCount;
        int itemsSeen = rental.rows.size() + sale.rows.size();
        int pagesRead = rental.pagesRead + sale.pagesRead;
        // 공공임대가 0건이면 서버 쪽 문제로 본다(12/5: 347행). 그대로 성공 처리하면 정규화가 임대 공고를 모두 끄려 한다
        if (rental.totalCount == 0 || !complete(rental) || !complete(sale)) {
            String msg = "마이홈 수집이 끝까지 되지 않았습니다 — 공공임대 " + rental.rows.size() + "/" + rental.totalCount
                    + "행, 공공분양 " + sale.rows.size() + "/" + sale.totalCount + "행";
            runs.finish(runId, CollectRunStore.FAILED, totalCount, itemsSeen, msg);
            throw new ExternalApiException(SOURCE, msg);
        }

        // (공고, 주택) 단위로 묶는다. 들어온 순서를 지켜 로그 · 원본을 읽기 쉽게 한다
        Map<String, List<JsonNode>> units = new LinkedHashMap<>();
        int skipped = group(Kind.RENT, rental.rows, units) + group(Kind.SALE, sale.rows, units);
        List<PolicyRawStore.RawItem> items = new ArrayList<>(units.size());
        units.forEach((policyNo, rows) -> items.add(new PolicyRawStore.RawItem(policyNo, toJsonArray(rows))));
        try {
            store.upsertAll(SOURCE, items, startedAt);
        } catch (RuntimeException e) {
            runs.finish(runId, CollectRunStore.FAILED, totalCount, itemsSeen, "원본 적재 실패: " + e.getMessage());
            throw e;
        }
        runs.finish(runId, CollectRunStore.SUCCESS, totalCount, itemsSeen, null);

        long rowsInTable = store.count(SOURCE);
        log.info("마이홈 수집 완료 — run={} 공공임대 {}행 · 공공분양 {}행 → (공고, 주택) {}건 · 번호 없어 건너뜀 {}행 · "
                        + "pagesRead={} policy_raw rows={}",
                runId, rental.rows.size(), sale.rows.size(), units.size(), skipped, pagesRead, rowsInTable);
        return new CollectResult(totalCount, pagesRead, itemsSeen, rowsInTable);
    }

    /** 한 기능(임대 또는 분양)의 전 페이지를 받아 out 에 쌓는다. 실패하면 예외. */
    private void fetchAll(Kind kind, String url, int perPage, Fetched out) {
        Page first = fetchPage(kind, url, 1, perPage);
        out.totalCount = first.totalCount;
        int totalPages = (first.totalCount + perPage - 1) / perPage;
        if (totalPages > MAX_PAGES) {
            throw new ExternalApiException(SOURCE, "마이홈 " + kind.label() + " 모집공고가 " + first.totalCount
                    + "행으로 상한 " + MAX_PAGES * perPage + "행을 넘습니다. 응답이 평소(12/5: 공공임대 347행)와 달라 받지 않습니다.");
        }
        log.info("마이홈 {} 수집 시작 — totalCount={} perPage={} totalPages={}", kind.label(), first.totalCount, perPage,
                totalPages);
        out.rows.addAll(first.rows);
        out.pagesRead = 1;

        for (int page = 2; page <= totalPages; page++) {
            sleepQuietly();
            Page next = fetchPage(kind, url, page, perPage);
            out.pagesRead++;
            out.rows.addAll(next.rows);
            if (next.rows.isEmpty()) {
                log.warn("마이홈 {} page={} 가 비어 있어 조기 종료합니다.", kind.label(), page);
                break;
            }
        }
    }

    private Page fetchPage(Kind kind, String url, int page, int perPage) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pageNo", String.valueOf(page));
        params.put("numOfRows", String.valueOf(perPage));
        String label = kind.prefix() + " page=" + page;
        JsonNode root = client.getJson(SOURCE, url, params, label);
        return parsePage(root, label);
    }

    /**
     * 응답 모양: {"response":{"header":{…},"body":{"item":[…],"totalCount":347,…}}} (12/5).
     * 공공데이터포털 응답은 항목이 하나면 배열이 아니라 객체 하나로 오는 경우가 있어 둘 다 읽는다.
     */
    static Page parsePage(JsonNode root, String label) {
        JsonNode body = root.path("response").path("body");
        int totalCount = JsonNodes.intValue(body.path("totalCount"), -1);
        if (totalCount < 0) {
            JsonNode header = root.path("response").path("header");
            String head = root.toString();
            throw new ExternalApiException(SOURCE, "응답에 totalCount 가 없습니다 (" + label + "). resultCode="
                    + JsonNodes.text(header.path("resultCode")) + ", resultMsg=" + JsonNodes.text(header.path("resultMsg"))
                    + ". 앞부분: " + DataGoKrClient.maskKey(head.length() <= 200 ? head : head.substring(0, 200) + "…"));
        }
        List<JsonNode> rows = new ArrayList<>();
        JsonNode item = body.path("item");
        if (item.isArray()) {
            for (int i = 0; i < item.size(); i++) {
                rows.add(item.get(i));
            }
        } else if (item.isObject()) {
            rows.add(item);
        }
        return new Page(totalCount, rows);
    }

    /** 행을 (공고, 주택) 묶음에 넣는다. 번호가 없어 넣지 못한 행 수를 돌려준다. */
    static int group(Kind kind, List<JsonNode> rows, Map<String, List<JsonNode>> units) {
        int skipped = 0;
        for (JsonNode row : rows) {
            String pblancId = JsonNodes.text(row.path("pblancId"));
            String houseSn = JsonNodes.text(row.path("houseSn"));
            if (pblancId == null || pblancId.isBlank() || houseSn == null || houseSn.isBlank()) {
                skipped++;
                log.warn("마이홈 {} 행에 pblancId · houseSn 이 없어 건너뜁니다.", kind.label());
                continue;
            }
            units.computeIfAbsent(policyNo(kind, pblancId, houseSn), k -> new ArrayList<>()).add(row);
        }
        return skipped;
    }

    static String policyNo(Kind kind, String pblancId, String houseSn) {
        return kind.prefix() + "-" + pblancId.strip() + "-" + houseSn.strip();
    }

    /** 받은 행들을 그대로 JSON 배열 하나로. 각 행의 글자는 바꾸지 않는다. */
    static String toJsonArray(List<JsonNode> rows) {
        List<String> parts = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            parts.add(row.toString());
        }
        return "[" + String.join(",", parts) + "]";
    }

    private static boolean complete(Fetched f) {
        return f.rows.size() >= f.totalCount * MIN_COMPLETE_RATIO;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(PAGE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalApiException(SOURCE, "수집이 중단되었습니다.");
        }
    }

    /** 한 페이지 응답에서 꺼낸 값. */
    record Page(int totalCount, List<JsonNode> rows) {}

    /** 한 기능을 받는 동안 쌓는 값. 실패했을 때도 여기까지 받은 수를 collect_run 에 남긴다. */
    private static final class Fetched {
        int totalCount;
        int pagesRead;
        final List<JsonNode> rows = new ArrayList<>();
    }
}
