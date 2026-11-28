package kr.hojun.policymatch.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

/**
 * 공공데이터포털 공통 클라이언트 (12/9). JDK 의 작은 HTTP 서버를 띄워, 실제로 어떤 주소가 나가는지와 오류 처리를 확인한다.
 */
@SpringBootTest
class DataGoKrClientTest {

    private static final String OK_BODY = "{\"currentCount\":0,\"data\":[],\"matchCount\":0}";
    /** 12/5 실제로 본 모양: 인증 · 기능 오류는 JSON 을 달라고 해도 HTTP 200 + XML 로 온다 */
    private static final String XML_ERROR = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
            + "<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode>"
            + "</cmmMsgHeader></OpenAPI_ServiceResponse>";

    @Autowired ObjectMapper objectMapper;

    private HttpServer server;
    private ExecutorService pool;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastRawQuery = new AtomicReference<>();
    private final AtomicReference<String> lastAccept = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    void 인증키와_조건_이름을_정확히_한_번_인코딩해_보낸다() throws IOException {
        // Decoding 키에는 + / = 가 들어 있다. '+' 를 그대로 보내면 서버가 공백으로 읽어 인증에 실패한다
        DataGoKrClient client = client("ab+c/d==", call -> 200, OK_BODY, 0);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", "1");
        params.put("cond[rcrt_prgs_yn::EQ]", "Y");

        client.getJson(Source.KSTARTUP, url(), params, "page=1");

        String query = lastRawQuery.get();
        assertTrue(query.startsWith("serviceKey=ab%2Bc%2Fd%3D%3D&"), query);
        assertTrue(query.contains("cond%5Brcrt_prgs_yn%3A%3AEQ%5D=Y"), query);
    }

    @Test
    void Encoding_키를_넣으면_호출하지_않고_알려_준다() throws IOException {
        // 12/5: Encoding 값을 넣으면 두 번 인코딩되어 SERVICE_KEY_IS_NOT_REGISTERED
        DataGoKrClient client = client("ab%2Bc", call -> 200, OK_BODY, 0);

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1"));

        assertTrue(e.getMessage().contains("Decoding"));
        assertEquals(0, calls.get());
    }

    @Test
    void 키가_비어_있으면_호출하지_않는다() throws IOException {
        DataGoKrClient client = client("", call -> 200, OK_BODY, 0);

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1"));

        assertTrue(e.getMessage().contains("DATA_GO_KR_KEY"));
        assertEquals(Source.KSTARTUP, e.source(), "어느 출처에서 났는지 담는다");
        assertEquals(0, calls.get());
    }

    @Test
    void XML_오류_응답은_오류_이름을_담아_실패한다() throws IOException {
        DataGoKrClient client = client("test", call -> 200, XML_ERROR, 0);

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1"));

        assertTrue(e.getMessage().contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR"), e.getMessage());
        assertEquals(1, calls.get(), "인증 오류는 다시 보내도 같다");
    }

    @Test
    void 일시적인_5xx는_다시_시도해_성공한다() throws IOException {
        DataGoKrClient client = client("test", call -> call == 1 ? 503 : 200, OK_BODY, 0);

        client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1");

        assertEquals(2, calls.get());
    }

    @Test
    void 계속_5xx면_3번_시도하고_포기한다() throws IOException {
        DataGoKrClient client = client("test", call -> 500, OK_BODY, 0);

        assertThrows(ExternalApiException.class, () -> client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1"));
        assertEquals(3, calls.get());
    }

    @Test
    void 그_밖의_4xx는_바로_포기한다() throws IOException {
        DataGoKrClient client = client("test", call -> 401, OK_BODY, 0);

        assertThrows(ExternalApiException.class, () -> client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1"));
        assertEquals(1, calls.get(), "다시 걸어도 결과가 같으므로 한 번만 호출");
    }

    @Test
    void 실패_메시지에_인증키를_남기지_않는다() throws IOException {
        // 타임아웃 오류 메시지에는 요청 주소가 통째로 들어 있다. 이 메시지가 collect_run · 로그 · 디스코드로 간다
        DataGoKrClient client = client("SECRETKEY123", call -> 200, OK_BODY, 500);

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1"));

        assertFalse(e.getMessage().contains("SECRETKEY123"), e.getMessage());
        assertTrue(e.getMessage().contains("시도=3"), "재시도까지 한 뒤 포기한다");
    }

    @Test
    void XML만_주는_기능은_XML을_달라고_보내고_본문을_그대로_준다() throws IOException {
        // 12/14 지방공공기관 채용: 성공 · 결과 없음 · 오류가 모두 HTTP 200 + XML 이다. 결과 코드는 수집기가 읽는다
        String xml = "<response><header><resultCode>3</resultCode><resultMsg>NONOTEXISTDATA_ERROR</resultMsg></header></response>";
        DataGoKrClient client = client("test", call -> 200, xml, 0);

        String body = client.getXml(Source.KSTARTUP, url(), Map.of("type", "xml"), "sidoCd=007005");

        assertEquals(xml, body);
        assertEquals("application/xml", lastAccept.get());
        assertTrue(lastRawQuery.get().startsWith("serviceKey=test&type=xml"), lastRawQuery.get());
    }

    @Test
    void XML_기능에_XML이_아닌_본문이_오면_실패한다() throws IOException {
        DataGoKrClient client = client("test", call -> 200, OK_BODY, 0);

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> client.getXml(Source.KSTARTUP, url(), Map.of(), "sidoCd=007001"));

        assertTrue(e.getMessage().contains("XML 대신"), e.getMessage());
    }

    @Test
    void JSON_기능은_JSON을_달라고_보낸다() throws IOException {
        DataGoKrClient client = client("test", call -> 200, OK_BODY, 0);

        client.getJson(Source.KSTARTUP, url(), Map.of(), "page=1");

        assertEquals("application/json", lastAccept.get());
    }

    // ---------- 도우미 ----------

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    /**
     * n 번째 호출에 statusFor(n) 코드와 본문을 돌려주는 서버를 띄우고, 그 서버를 부르는 클라이언트를 만든다.
     *
     * @param delayMs 응답 전에 기다리는 시간. 클라이언트 읽기 타임아웃(100ms)보다 길면 타임아웃이 난다
     */
    private DataGoKrClient client(String key, IntUnaryOperator statusFor, String okBody, long delayMs) throws IOException {
        pool = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(pool);
        server.createContext("/", exchange -> {
            int status = statusFor.applyAsInt(calls.incrementAndGet());
            lastRawQuery.set(exchange.getRequestURI().getRawQuery());
            lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = (status == 200 ? okBody : "{\"error\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException ignored) {
                // 클라이언트가 타임아웃으로 먼저 끊은 경우
            }
        });
        server.start();

        DataGoKrProperties props = new DataGoKrProperties();
        props.setKey(key);
        props.setReadTimeout(Duration.ofMillis(100));
        props.setRetryBaseDelay(Duration.ofMillis(10));
        return new DataGoKrClient(props, objectMapper);
    }
}
