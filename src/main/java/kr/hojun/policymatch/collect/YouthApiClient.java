package kr.hojun.policymatch.collect;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 온통청년 청년정책 API 호출 담당.
 *
 * 두 가지를 반드시 처리한다.
 *  1) 타임아웃 — 상대 서버가 응답하지 않을 때 우리 스레드가 무한정 붙잡히지 않도록 한다.
 *  2) HTML 응답 감지 — 인증키가 틀리면 이 API 는 JSON 대신 로그인 HTML 을 HTTP 200 으로 돌려준다.
 *     그대로 파싱하면 엉뚱한 곳에서 터지므로, 본문 첫 글자가 '<' 이면 즉시 예외로 전환한다.
 */
@Component
public class YouthApiClient {

    private static final Logger log = LoggerFactory.getLogger(YouthApiClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;

    private final YouthApiProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public YouthApiClient(YouthApiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "policy-match/1.0")
                .build();
    }

    /** 한 페이지를 가져와 JSON 트리로 돌려준다. */
    public JsonNode fetchPage(int pageNum) {
        if (props.getKey() == null || props.getKey().isBlank()) {
            throw new YouthApiException(
                    "youth.api.key 가 비어 있습니다. .env 에 YOUTH_API_KEY 를 설정하십시오.");
        }

        URI uri = UriComponentsBuilder.fromUriString(props.getBaseUrl())
                .queryParam("apiKeyNm", props.getKey())
                .queryParam("pageNum", pageNum)
                .queryParam("pageSize", props.getPageSize())
                .queryParam("rtnType", "json")
                .encode()
                .build()
                .toUri();

        String body = getWithRetry(uri, pageNum);

        if (body == null || body.isBlank()) {
            throw new YouthApiException("응답 본문이 비어 있습니다 (page=" + pageNum + ")");
        }

        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<")) {
            String head = trimmed.substring(0, Math.min(120, trimmed.length()));
            throw new YouthApiException(
                    "JSON 이 아니라 HTML 이 돌아왔습니다. 인증키 오류일 가능성이 큽니다. 앞부분: " + head);
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            log.debug("page={} resultCode={}", pageNum, JsonNodes.text(root.path("resultCode")));
            return root;
        } catch (RuntimeException e) {
            throw new YouthApiException("JSON 파싱 실패 (page=" + pageNum + ")", e);
        }
    }

    /**
     * 공공 API 는 간헐적으로 5xx 나 연결 끊김을 낸다. 한 번 실패했다고 28페이지짜리 수집을
     * 통째로 버리는 것은 낭비이므로, 같은 페이지를 짧은 간격으로 다시 시도한다.
     * 다만 인증키 오류 같은 4xx 는 몇 번을 다시 걸어도 결과가 같으므로 즉시 포기한다.
     */
    private String getWithRetry(URI uri, int pageNum) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.get().uri(uri).retrieve().body(String.class);

            } catch (HttpClientErrorException e) {
                // 4xx — 재시도해도 소용없다. 단 429(요청 과다)만 예외로 둔다.
                if (e.getStatusCode().value() != 429) {
                    throw new YouthApiException(describe(pageNum, attempt, e), e);
                }
                last = e;

            } catch (ResourceAccessException | HttpServerErrorException e) {
                // 타임아웃 · 연결 끊김 · 5xx — 잠시 뒤 다시 시도한다.
                last = e;

            } catch (RestClientException e) {
                throw new YouthApiException(describe(pageNum, attempt, e), e);
            }

            if (attempt < MAX_ATTEMPTS) {
                long waitMs = RETRY_BASE_DELAY_MS * attempt;
                log.warn("page={} {}회차 실패, {}ms 뒤 재시도 — {}", pageNum, attempt, waitMs,
                        last == null ? "?" : last.getMessage());
                sleepQuietly(waitMs);
            }
        }

        throw new YouthApiException(describe(pageNum, MAX_ATTEMPTS, last), last);
    }

    /** 원인을 삼키지 않는다. 예외 종류와 원문 메시지를 그대로 남겨야 로그 없이도 판별된다. */
    private String describe(int pageNum, int attempt, Throwable cause) {
        String type = cause == null ? "알 수 없음" : cause.getClass().getSimpleName();
        String msg = cause == null ? "" : String.valueOf(cause.getMessage());
        return "API 호출 실패 (page=" + pageNum + ", 시도=" + attempt + ") " + type + ": " + msg;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouthApiException("호출이 중단되었습니다.", e);
        }
    }
}
