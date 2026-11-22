package kr.hojun.policymatch.match;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 상세 조회 요청 제한 단위 테스트 (D-027). DB 도 스프링도 띄우지 않는다.
 * 시계를 바꿔 끼워 "1분이 지나면 다시 받는다"를 기다리지 않고 시험한다.
 */
class PolicyDetailRateLimiterTest {

    /** 테스트가 마음대로 앞으로 돌릴 수 있는 시계 */
    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-24T03:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final PolicyDetailRateLimiter limiter = new PolicyDetailRateLimiter(clock);

    @Test
    void 한_IP는_1분에_30번까지_받고_31번째는_거절한다() {
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.tryAcquire("203.0.113.1"), (i + 1) + "번째");
        }
        assertFalse(limiter.tryAcquire("203.0.113.1"));
    }

    @Test
    void 다른_IP는_따로_센다() {
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire("203.0.113.1");
        }
        assertTrue(limiter.tryAcquire("203.0.113.2"));
    }

    @Test
    void 한도_시간_1분이_지나면_다시_받는다() {
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire("203.0.113.1");
        }
        assertFalse(limiter.tryAcquire("203.0.113.1"));

        clock.now = clock.now.plus(Duration.ofMinutes(1)).plusSeconds(1);

        assertTrue(limiter.tryAcquire("203.0.113.1"));
    }
}
