package kr.hojun.policymatch.collect;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 지방공공기관 채용공고를 시도 코드별로 받아 policy_raw 에 source = cleaneye 로 적재한다 (12/14).
 *
 * 설계 메모 (근거: docs/sources/local-employ.md, 12/5 측정)
 *  - 이 기능은 XML 만 주고, 페이지 파라미터가 없다. 시도 코드(클린아이 자체 코드 007001 ~)마다 한 번 부르면 그 시도의 공고가
 *    한 번에 온다(12/5: 경기 103행도 한 번에 옴). 그래서 시도 코드 20개를 차례로 부른다.
 *  - 응답의 totalCount 는 항목이 있어도 늘 0 이라 믿지 않는다. 성공 판단은 D-029 ④대로 "모든 시도 코드가 응답했는가"다:
 *    결과 코드 0(SUCCESS_INFO)이나 3(NONOTEXISTDATA_ERROR = 그 시도에 공고 없음)이면 응답한 것이다. 하나라도 다른 코드 ·
 *    HTTP 오류면 FAILED 다(그 시도 공고가 통째로 비활성되는 것을 막는다). 전체가 0건이어도 FAILED 다(서버 쪽 문제로 봄).
 *  - collect_run.tot_count · items_seen 은 둘 다 받은 행 수다(믿을 수 있는 전체 건수가 없다).
 *  - 응답 한 행 = 공고의 직급 · 직무 하나다(12/5: 442행 = 공고 420개). 행마다 번호(NO)가 달라 행 단위로 넣는다.
 *  - 원본은 XML 의 item 하나를 칸 이름 → 글자 그대로의 JSON 객체로 바꿔 넣는다(raw_json 칸이 JSON 형식이라).
 *    값은 손대지 않는다: 'null' · '-' 같은 글자도 그대로 두고, 정규화에서 빈 값으로 본다.
 *  - XML 을 읽을 때 DOCTYPE · 외부 엔티티를 막는다(XXE). 공공데이터포털 응답에는 DOCTYPE 이 없다.
 */
@Service
public class CleaneyeCollector implements SourceCollector {

    private static final Logger log = LoggerFactory.getLogger(CleaneyeCollector.class);
    private static final Source SOURCE = Source.CLEANEYE;
    private static final long CALL_DELAY_MS = 200; // 상대 서버 배려

    /** 결과 코드: 성공 · 결과 없음 (12/5 실측. 결과 없음은 그 시도에 공고가 없다는 정상 응답이다) */
    static final int SUCCESS_CODE = 0;
    static final int NO_DATA_CODE = 3;

    private final DataGoKrClient client;
    private final PolicyRawStore store;
    private final CollectRunStore runs;
    private final CleaneyeProperties props;
    private final ObjectMapper objectMapper;

    public CleaneyeCollector(DataGoKrClient client, PolicyRawStore store, CollectRunStore runs,
                             CleaneyeProperties props, ObjectMapper objectMapper) {
        this.client = client;
        this.store = store;
        this.runs = runs;
        this.props = props;
        this.objectMapper = objectMapper;
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
        List<String> codes = props.getSidoCodes();

        int itemsSeen = 0;
        int responded = 0;
        int empty = 0;
        try {
            if (codes == null || codes.isEmpty()) {
                throw new ExternalApiException(SOURCE, "cleaneye.api.sido-codes 가 비어 있습니다.");
            }
            log.info("지방공공기관 수집 시작 — run={} 시도 코드 {}개", runId, codes.size());
            for (int i = 0; i < codes.size(); i++) {
                if (i > 0) {
                    sleepQuietly();
                }
                String code = codes.get(i);
                String label = "sidoCd=" + code;
                Map<String, String> params = new LinkedHashMap<>();
                params.put("sidoCd", code);
                params.put("type", "xml");
                List<Map<String, String>> items = parse(client.getXml(SOURCE, props.getBaseUrl(), params, label), label);
                responded++;
                if (items.isEmpty()) {
                    empty++;
                    continue;
                }
                itemsSeen += save(items, startedAt, label);
            }
        } catch (RuntimeException e) {
            runs.finish(runId, CollectRunStore.FAILED, itemsSeen, itemsSeen,
                    "시도 코드 " + responded + "/" + (codes == null ? 0 : codes.size()) + "개 응답 후 실패: " + e.getMessage());
            throw e;
        }

        if (itemsSeen == 0) {
            String msg = "지방공공기관 수집 결과가 0건입니다 — 시도 코드 " + responded + "개가 모두 결과 없음. 서버 쪽 문제로 봅니다.";
            runs.finish(runId, CollectRunStore.FAILED, 0, 0, msg);
            throw new ExternalApiException(SOURCE, msg);
        }
        runs.finish(runId, CollectRunStore.SUCCESS, itemsSeen, itemsSeen, null);

        long rows = store.count(SOURCE);
        log.info("지방공공기관 수집 완료 — run={} 시도 코드 {}개 응답(결과 없음 {}) itemsSeen={} policy_raw rows={}",
                runId, responded, empty, itemsSeen, rows);
        // pagesRead 칸에는 부른 시도 코드 수를 적는다(이 기능은 페이지가 없다)
        return new CollectResult(itemsSeen, responded, itemsSeen, rows);
    }

    /**
     * 응답 XML 에서 item 들을 꺼낸다. 결과 코드 0 이면 item 목록, 3 이면 빈 목록, 그 밖은 실패.
     * 응답 모양 (12/5): {@code <response><openXmlEmployInfo-header><resultCode>0</resultCode>…</openXmlEmployInfo-header>
     * <openXmlEmployInfo-body><items><item><ENT_NAME>…</ENT_NAME>…</item></items><totalCount>0</totalCount>…}
     * 공공데이터포털 게이트웨이 오류(인증키 · 한도)는 {@code <OpenAPI_ServiceResponse>…<returnAuthMsg>} 모양이라 결과 코드가 없다.
     */
    static List<Map<String, String>> parse(String xml, String label) {
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // 기본 처리기는 오류를 표준 오류로 찍는다. 예외만 던지게 한다
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            root = doc.getDocumentElement();
        } catch (Exception e) {
            throw new ExternalApiException(SOURCE, "XML 을 읽지 못했습니다 (" + label + "): " + e.getClass().getSimpleName()
                    + ". 앞부분: " + head(xml));
        }

        String code = firstText(root, "resultCode");
        int resultCode = parseCode(code);
        if (resultCode == NO_DATA_CODE) {
            return List.of();
        }
        if (resultCode != SUCCESS_CODE) {
            String msg = firstText(root, "resultMsg");
            if (code == null) {
                // 게이트웨이 오류 모양: returnAuthMsg · returnReasonCode
                code = firstText(root, "returnReasonCode");
                msg = firstText(root, "returnAuthMsg");
            }
            throw new ExternalApiException(SOURCE, "지방공공기관 응답이 성공이 아닙니다 (" + label + "). resultCode=" + code
                    + ", resultMsg=" + msg + ". 앞부분: " + head(xml));
        }

        List<Map<String, String>> items = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            Map<String, String> fields = new LinkedHashMap<>();
            NodeList children = nodes.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    fields.put(child.getNodeName(), child.getTextContent());
                }
            }
            items.add(fields);
        }
        return items;
    }

    /** item 들을 (NO, JSON) 으로 넣고 넣은 수를 돌려준다. NO 가 없는 행은 건너뛴다. */
    private int save(List<Map<String, String>> items, LocalDateTime fetchedAt, String label) {
        List<PolicyRawStore.RawItem> rows = new ArrayList<>(items.size());
        for (Map<String, String> item : items) {
            String no = item.get("NO");
            if (no == null || no.isBlank()) {
                log.warn("지방공공기관 NO 가 없는 행을 건너뜁니다 ({}).", label);
                continue;
            }
            rows.add(new PolicyRawStore.RawItem(no.strip(), objectMapper.writeValueAsString(item)));
        }
        return store.upsertAll(SOURCE, rows, fetchedAt);
    }

    private static int parseCode(String code) {
        try {
            return code == null ? -1 : Integer.parseInt(code.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String firstText(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().strip();
    }

    private static String head(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return DataGoKrClient.maskKey(oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…");
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(CALL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalApiException(SOURCE, "수집이 중단되었습니다.");
        }
    }
}
