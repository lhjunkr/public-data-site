package kr.hojun.policymatch.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 의견 입력 검증 · 동의 · 도배 방지 (D-024). DB 없이 가짜 저장소로 규칙만 본다. */
class FeedbackServiceTest {

    /** 진짜 DB 대신 받은 것만 기억하는 저장소 */
    static class FakeStore extends FeedbackStore {
        final List<String[]> saved = new ArrayList<>();
        int today = 0;

        FakeStore() { super(null); }

        @Override public void insert(String message, String email) { saved.add(new String[] {message, email}); }
        @Override public int countToday() { return today; }
    }

    /** 손으로 앞으로 돌릴 수 있는 시계 */
    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-02T03:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final FakeStore store = new FakeStore();
    private final MovableClock clock = new MovableClock();
    private final FeedbackService service = new FeedbackService(store, new FeedbackRateLimiter(clock));

    private HttpStatus statusOf(FeedbackRequest request, String ip) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submit(request, ip));
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void 의견만_있으면_이메일_없이_저장한다() {
        assertTrue(service.submit(new FeedbackRequest("  결과가 이상해요  ", null, null, null), "1.1.1.1"));

        assertEquals("결과가 이상해요", store.saved.get(0)[0], "앞뒤 공백은 지운다");
        assertNull(store.saved.get(0)[1]);
    }

    @Test
    void 의견이_비었거나_1000자를_넘으면_400() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(new FeedbackRequest("   ", null, null, null), "1.1.1.1"));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(new FeedbackRequest("가".repeat(1001), null, null, null), "1.1.1.1"));
        assertTrue(service.submit(new FeedbackRequest("가".repeat(1000), null, null, null), "1.1.1.1"), "1000자는 통과");
    }

    @Test
    void 이메일은_동의가_있어야_저장한다() {
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(new FeedbackRequest("답장 주세요", "me@example.com", false, null), "1.1.1.1"));
        assertEquals(0, store.saved.size(), "동의 없이 이메일을 받지 않는다");

        service.submit(new FeedbackRequest("답장 주세요", "me@example.com", true, null), "1.1.1.1");
        assertEquals("me@example.com", store.saved.get(0)[1]);
    }

    @Test
    void 이메일_형식이_틀리면_400() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(new FeedbackRequest("의견", "me@", true, null), "1.1.1.1"));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(new FeedbackRequest("의견", "me example.com", true, null), "1.1.1.1"));
    }

    @Test
    void 보이지_않는_칸을_채운_요청은_성공처럼_답하고_저장하지_않는다() {
        assertFalse(service.submit(new FeedbackRequest("광고", null, null, "http://spam.example"), "1.1.1.1"));
        assertEquals(0, store.saved.size());
    }

    @Test
    void 같은_IP는_10분에_3번까지_다른_IP는_따로_센다() {
        FeedbackRequest ok = new FeedbackRequest("의견", null, null, null);
        for (int i = 0; i < 3; i++) {
            service.submit(ok, "1.1.1.1");
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf(ok, "1.1.1.1"));
        assertTrue(service.submit(ok, "2.2.2.2"), "다른 사람은 영향 없음");

        clock.now = clock.now.plusSeconds(10 * 60 + 1);
        assertTrue(service.submit(ok, "1.1.1.1"), "10분이 지나면 다시 받는다");
    }

    @Test
    void 하루_상한을_넘으면_429() {
        store.today = FeedbackService.DAILY_LIMIT;
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf(new FeedbackRequest("의견", null, null, null), "3.3.3.3"));
    }
}
