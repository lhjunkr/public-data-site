package kr.hojun.policymatch.alert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 디스코드 알림의 원칙 ① ② (D-026). 진짜 디스코드로는 보내지 않는다. */
class DiscordAlertSenderTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void 주소가_비어_있으면_보내지_않고_조용히_끝난다() {
        assertDoesNotThrow(() -> new DiscordAlertSender("", mapper).send("테스트"));
        assertDoesNotThrow(() -> new DiscordAlertSender(null, mapper).send("테스트"));
    }

    @Test
    void 보내다_실패해도_예외를_던지지_않는다() {
        // 127.0.0.1 의 1번 포트에는 아무것도 없어 연결이 바로 거부된다 (디스코드 장애 흉내)
        DiscordAlertSender sender = new DiscordAlertSender("http://127.0.0.1:1/webhook", mapper);

        long started = System.currentTimeMillis();
        assertDoesNotThrow(() -> sender.send("테스트"));
        assertTrue(System.currentTimeMillis() - started < 5000, "타임아웃 안에 끝난다");
    }
}
