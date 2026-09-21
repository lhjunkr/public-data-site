package kr.hojun.policymatch.collect;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 온통청년 API 를 전 페이지 순회하며 원본을 policy_raw 에 적재한다.
 *
 * 설계 메모
 *  - 정규화는 여기서 하지 않는다. 수집은 "원본 그대로", 가공은 별도 단계로 분리한다.
 *    API 응답 형태가 바뀌거나 정규화 규칙이 틀려도 원본이 남아 있으면 다시 만들 수 있다.
 *  - totCount 는 신뢰하되 맹신하지 않는다. 수집 도중 정책이 추가·삭제되면 값이 흔들리므로
 *    (docs/decisions.md 의 미해결 이슈 O-4), 빈 페이지를 만나면 그 자리에서 멈춘다.
 */
@Service
public class PolicyCollector {

    private static final Logger log = LoggerFactory.getLogger(PolicyCollector.class);
    private static final int MAX_PAGES = 200;      // 폭주 방지 상한
    private static final long PAGE_DELAY_MS = 200; // 상대 서버 배려

    private final YouthApiClient client;
    private final PolicyRawStore store;
    private final YouthApiProperties props;

    public PolicyCollector(YouthApiClient client, PolicyRawStore store, YouthApiProperties props) {
        this.client = client;
        this.store = store;
        this.props = props;
    }

    public CollectResult collectAll() {
        LocalDateTime startedAt = LocalDateTime.now();

        JsonNode first = client.fetchPage(1);
        int totCount = JsonNodes.intValue(first.path("result").path("pagging").path("totCount"), 0);
        int pageSize = Math.max(1, props.getPageSize());
        int totalPages = Math.min(MAX_PAGES, (totCount + pageSize - 1) / pageSize);

        log.info("수집 시작 — totCount={} pageSize={} totalPages={}", totCount, pageSize, totalPages);

        int itemsSeen = savePage(first, startedAt);
        int pagesRead = 1;

        for (int page = 2; page <= totalPages; page++) {
            JsonNode node = client.fetchPage(page);
            int saved = savePage(node, startedAt);
            pagesRead++;
            itemsSeen += saved;

            if (saved == 0) {
                log.warn("page={} 가 비어 있어 수집을 조기 종료합니다.", page);
                break;
            }
            sleepQuietly();
        }

        long rows = store.count();
        log.info("수집 완료 — pagesRead={} itemsSeen={} policy_raw rows={}", pagesRead, itemsSeen, rows);
        return new CollectResult(totCount, pagesRead, itemsSeen, rows);
    }

    private int savePage(JsonNode root, LocalDateTime fetchedAt) {
        JsonNode list = root.path("result").path("youthPolicyList");
        if (!list.isArray()) {
            throw new YouthApiException("응답에 youthPolicyList 배열이 없습니다. resultMessage="
                    + JsonNodes.text(root.path("resultMessage")));
        }

        List<PolicyRawStore.RawItem> items = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            JsonNode item = list.get(i);
            String policyNo = JsonNodes.text(item.path("plcyNo"));
            if (policyNo == null || policyNo.isBlank()) {
                log.warn("plcyNo 가 없는 항목을 건너뜁니다.");
                continue;
            }
            items.add(new PolicyRawStore.RawItem(policyNo, item.toString()));
        }
        return store.upsertAll(items, fetchedAt);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(PAGE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouthApiException("수집이 중단되었습니다.", e);
        }
    }
}
