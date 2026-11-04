package kr.hojun.policymatch.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 프록시(Caddy) 뒤에서도 의견 보내기 요청 제한이 사용자 IP마다 따로 걸리는지 확인한다 (D-025).
 * MockMvc 는 실제 Tomcat 을 거치지 않아 X-Forwarded-For 처리를 시험할 수 없으므로, 실제 서버를 띄운다.
 * 이 테스트에서 저장한 의견은 트랜잭션으로 되돌릴 수 없어 끝나고 직접 지운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedbackProxyIpTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM feedback WHERE message LIKE 'T-PROXY%'");
    }

    /** 프록시가 원래 사용자 IP를 X-Forwarded-For 에 적어 보내는 상황을 흉내 낸다. 응답 상태 코드를 돌려준다 */
    private int sendAs(String userIp) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/feedback"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", userIp)
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"T-PROXY 테스트\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void 프록시가_넘긴_사용자_IP마다_요청_제한이_따로_걸린다() throws Exception {
        String userA = "203.0.113.10";
        String userB = "203.0.113.20";

        for (int i = 0; i < 3; i++) {
            assertEquals(201, sendAs(userA));
        }

        assertEquals(429, sendAs(userA));

        assertEquals(201, sendAs(userB));
    }
}