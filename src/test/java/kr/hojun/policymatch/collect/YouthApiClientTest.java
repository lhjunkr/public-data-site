package kr.hojun.policymatch.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

/**
 * 재시도 규칙 테스트 (D-017, D-022). JDK 에 들어 있는 작은 HTTP 서버를 띄워 실제 HTTP 응답 코드를 돌려준다.
 */
@SpringBootTest
class YouthApiClientTest {

    private static final String OK_BODY = "{\"resultCode\":200,\"result\":{\"youthPolicyList\":[]}}";
    private static final String E002_BODY = "{\"errorCode\":\"e002\",\"errorMsg\":\"invalid param data.\"}";

    @Autowired ObjectMapper objectMapper;

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 일시적인_400은_다시_시도해_성공한다() throws IOException {
        // 10/30 관측: 9페이지가 400(e002)으로 실패했는데 같은 요청을 다시 보내자 200 이었다
        YouthApiClient client = clientReturning(call -> call == 1 ? 400 : 200);

        client.fetchPage(1);

        assertEquals(2, calls.get());
    }

    @Test
    void 일시적인_403도_다시_시도해_성공한다() throws IOException {
        // 11/1 관측: 31페이지가 403(e001 invalid api key)으로 실패. 같은 키로 앞 30페이지는 성공했다
        YouthApiClient client = clientReturning(call -> call == 1 ? 403 : 200);

        client.fetchPage(1);

        assertEquals(2, calls.get());
    }

    @Test
    void 계속_403이면_3번_시도하고_포기한다() throws IOException {
        // 인증키가 정말 틀린 경우. 끝없이 다시 걸지 않는다
        YouthApiClient client = clientReturning(call -> 403);

        assertThrows(YouthApiException.class, () -> client.fetchPage(1));
        assertEquals(3, calls.get());
    }

    @Test
    void 그_밖의_4xx는_바로_포기한다() throws IOException {
        YouthApiClient client = clientReturning(call -> 404);

        assertThrows(YouthApiException.class, () -> client.fetchPage(1));
        assertEquals(1, calls.get(), "다시 걸어도 결과가 같으므로 한 번만 호출");
    }

    // ---------- 도우미 ----------

    /** n 번째 호출에 statusFor(n) 코드를 돌려주는 서버를 띄우고, 그 서버를 부르는 클라이언트를 만든다. */
    private YouthApiClient clientReturning(IntUnaryOperator statusFor) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = statusFor.applyAsInt(calls.incrementAndGet());
            byte[] body = (status == 200 ? OK_BODY : E002_BODY).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        YouthApiProperties props = new YouthApiProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        props.setKey("test");
        return new YouthApiClient(props, objectMapper);
    }
}
