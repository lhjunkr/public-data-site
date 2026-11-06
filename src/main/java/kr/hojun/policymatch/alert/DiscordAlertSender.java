package kr.hojun.policymatch.alert;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * 디스코드 웹훅으로 알림을 보낸다 (D-026).
 *
 *  ① 설정 없으면 꺼짐 — alert.webhook-url 이 비어 있으면 보내지 않는다. 맥 · CI · 테스트가 실제로 보내지 않게 한다.
 *     AdminToken 은 비면 503 으로 막지만, 알림은 부가 기능이라 조용히 넘어간다.
 *  ② 실패해도 본 작업 계속 — 예외를 밖으로 던지지 않고 로그만 남긴다.
 *  ③ 타임아웃 — 연결 2초, 읽기 3초. 디스코드가 응답하지 않아도 요청이 붙잡히지 않는다.
 *  로그에 예외 메시지를 그대로 남기지 않는다. 연결 오류 메시지에는 웹훅 URL(토큰 포함)이 들어 있다.
 */
@Component
public class DiscordAlertSender implements AlertSender {

    private static final Logger log = LoggerFactory.getLogger(DiscordAlertSender.class);

    /** 디스코드 메시지 한 개의 상한은 2,000자. 여유를 둔다 */
    private static final int MAX_LENGTH = 1900;

    private final String webhookUrl;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DiscordAlertSender(@Value("${alert.webhook-url:}") String webhookUrl, ObjectMapper objectMapper) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void send(String text) {
        if (webhookUrl.isEmpty() || text == null) {
            return;
        }
        String content = text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH) + "…";
        try {
            // allowed_mentions.parse = [] : 글 안에 @everyone 이 있어도 아무도 호출하지 않는다
            String json = objectMapper.writeValueAsString(Map.of(
                    "content", content,
                    "allowed_mentions", Map.of("parse", List.of())));
            restClient.post()
                    .uri(URI.create(webhookUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("알림 전송 실패 — HTTP {}", e.getStatusCode().value());
        } catch (RuntimeException e) {
            log.warn("알림 전송 실패 — {}", e.getClass().getSimpleName());
        }
    }
}
