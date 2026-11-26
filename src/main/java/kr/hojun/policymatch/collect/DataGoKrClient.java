package kr.hojun.policymatch.collect;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 공공데이터포털(apis.data.go.kr) API 를 부르는 공통 클라이언트. K-Startup 부터 쓰고, 마이홈 · 잡알리오도 같은 것을 쓴다.
 *
 * 출처별 수집기는 "주소 + 파라미터"만 넘기고, 여기서 다음을 맡는다.
 *  1) 인증키를 정확히 한 번 인코딩한다. 키에 든 '+' 를 그대로 보내면 서버가 공백으로 읽어
 *     SERVICE_KEY_IS_NOT_REGISTERED 가 난다. 이미 인코딩된 Encoding 키를 넣으면 두 번 인코딩되므로 호출 전에 막는다(12/5).
 *  2) 타임아웃 · 재시도. 연결 끊김 · 타임아웃 · 5xx · 429 는 같은 요청을 최대 3번 보낸다. 그 밖의 4xx 는 바로 포기한다.
 *  3) 오류 응답 감지. 인증키 · 승인 · 기능 이름 오류는 JSON 을 달라고 해도 HTTP 200 + XML 로 온다
 *     (예: NO_OPENAPI_SERVICE_ERROR, 12/5). 본문 첫 글자가 '<' 이면 오류로 바꾼다.
 *  4) 인증키를 남기지 않는다. 연결 오류 메시지에는 요청 주소가 통째로 들어 있어 키가 섞인다.
 *     예외 메시지는 collect_run.message · 서버 로그 · 디스코드 알림으로 퍼지므로, 만들 때 키를 가리고 원래 예외는 붙이지 않는다.
 */
@Component
public class DataGoKrClient {

    private static final Logger log = LoggerFactory.getLogger(DataGoKrClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int HEAD_MAX = 200;

    private final DataGoKrProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DataGoKrClient(DataGoKrProperties props, ObjectMapper objectMapper) {
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

    /**
     * GET 한 번(실패하면 최대 3번)을 보내고 JSON 트리로 돌려준다.
     *
     * @param source 오류에 담을 출처
     * @param url    기능 주소 (예: …/kisedKstartupService02/getAnnouncementInformation02)
     * @param params 인증키를 뺀 나머지 파라미터. 이름 · 값 모두 인코딩하지 않은 글자로 넘긴다
     * @param label  로그 · 오류에 붙일 위치 표시 (예: "page=2")
     */
    public JsonNode getJson(Source source, String url, Map<String, String> params, String label) {
        String key = props.getKey();
        if (key == null || key.isBlank()) {
            throw new ExternalApiException(source,
                    "data-go-kr.key 가 비어 있습니다. .env 에 DATA_GO_KR_KEY(공공데이터포털 일반 인증키 Decoding 값)를 설정하십시오.");
        }
        if (key.contains("%")) {
            throw new ExternalApiException(source,
                    "DATA_GO_KR_KEY 가 Encoding 값으로 보입니다('%' 포함). 두 번 인코딩되어 인증에 실패하므로 Decoding 값을 넣으십시오.");
        }

        URI uri = buildUri(url, key, params);
        byte[] bytes = getWithRetry(source, uri, label);
        // 응답 헤더에 문자 집합이 없을 때 기본값(ISO-8859-1)으로 읽어 한글이 깨지지 않게, 바이트로 받아 UTF-8 로 직접 읽는다
        String body = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);

        if (body.isBlank()) {
            throw new ExternalApiException(source, "응답 본문이 비어 있습니다 (" + label + ")");
        }
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<")) {
            throw new ExternalApiException(source, "JSON 대신 XML 이 돌아왔습니다 (" + label + "). 공공데이터포털 오류 응답입니다"
                    + " — 인증키 · 활용신청 승인 · 기능 이름 · 일일 한도를 확인하십시오. 앞부분: " + head(trimmed));
        }

        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ExternalApiException(source, "JSON 파싱 실패 (" + label + "). 앞부분: " + head(trimmed));
        }
    }

    /**
     * 주소를 직접 만든다. 이름 · 값을 모두 정확히 한 번 인코딩한다.
     * 스프링의 UriComponentsBuilder.encode() 는 쿼리 값의 '+' 를 허용 문자로 보고 그대로 둔다.
     * 그러면 서버가 '+' 를 공백으로 읽어 인증키가 틀어지므로 쓰지 않는다.
     */
    static URI buildUri(String url, String key, Map<String, String> params) {
        StringBuilder query = new StringBuilder("serviceKey=").append(encode(key));
        params.forEach((name, value) -> query.append('&').append(encode(name)).append('=').append(encode(value)));
        return URI.create(url + (url.contains("?") ? "&" : "?") + query);
    }

    /** URLEncoder 는 공백을 '+' 로 바꾼다. 주소의 쿼리에서는 %20 이 확실하다. */
    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 글 안의 serviceKey=… 값을 가린다. 인코딩된 값도 함께 가린다. */
    static String maskKey(String text) {
        return text == null ? null : text.replaceAll("serviceKey=[^&\"\\s]+", "serviceKey=***");
    }

    private byte[] getWithRetry(Source source, URI uri, String label) {
        String lastFailure = "알 수 없음";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.get().uri(uri).retrieve().body(byte[].class);

            } catch (HttpClientErrorException e) {
                // 4xx — 429(요청 과다)만 다시 시도한다. 인증 · 주소 오류는 몇 번을 보내도 같다
                lastFailure = describe(e);
                if (e.getStatusCode().value() != 429) {
                    throw failure(source, label, attempt, lastFailure);
                }

            } catch (ResourceAccessException | HttpServerErrorException e) {
                // 타임아웃 · 연결 끊김 · 5xx — 잠시 뒤 다시 시도한다
                lastFailure = describe(e);

            } catch (RestClientException e) {
                throw failure(source, label, attempt, describe(e));
            }

            if (attempt < MAX_ATTEMPTS) {
                long waitMs = props.getRetryBaseDelay().toMillis() * attempt;
                log.warn("{} {} {}회차 실패, {}ms 뒤 재시도 — {}", source.code(), label, attempt, waitMs, lastFailure);
                sleepQuietly(source, waitMs);
            }
        }
        throw failure(source, label, MAX_ATTEMPTS, lastFailure);
    }

    private static ExternalApiException failure(Source source, String label, int attempt, String reason) {
        return new ExternalApiException(source, "API 호출 실패 (" + label + ", 시도=" + attempt + ") " + reason);
    }

    /** 원인의 종류 · 상태 코드 · 응답 앞부분을 남기되, 인증키는 가린다. */
    private static String describe(RestClientException e) {
        StringBuilder text = new StringBuilder(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        if (e instanceof HttpStatusCodeException status) {
            String body = status.getResponseBodyAsString(StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                text.append(" / 응답: ").append(head(body.strip()));
            }
        }
        return maskKey(text.toString());
    }

    private static String head(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return maskKey(oneLine.length() <= HEAD_MAX ? oneLine : oneLine.substring(0, HEAD_MAX) + "…");
    }

    private static void sleepQuietly(Source source, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalApiException(source, "호출이 중단되었습니다.");
        }
    }
}
