package kr.hojun.policymatch.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 검색 요청 제한 단위 테스트 (12/3). DB 도 스프링도 띄우지 않는다.
 * 시계는 상세 조회 테스트의 MovableClock 을 같이 쓴다.
 */
class MatchRateLimiterTest {

    private final PolicyDetailRateLimiterTest.MovableClock clock = new PolicyDetailRateLimiterTest.MovableClock();
    private final MatchRateLimiter limiter = new MatchRateLimiter(20, clock);

    @Test
    void 한_IP는_1분에_20번까지_받고_21번째는_거절한다() {
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire("203.0.113.1"), (i + 1) + "번째");
        }
        assertFalse(limiter.tryAcquire("203.0.113.1"));
    }

    @Test
    void 다른_IP는_따로_센다() {
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire("203.0.113.1");
        }
        assertTrue(limiter.tryAcquire("203.0.113.2"));
    }

    @Test
    void 한도_시간_1분이_지나면_다시_받는다() {
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire("203.0.113.1");
        }
        assertFalse(limiter.tryAcquire("203.0.113.1"));

        clock.now = clock.now.plus(Duration.ofMinutes(1)).plusSeconds(1);

        assertTrue(limiter.tryAcquire("203.0.113.1"));
    }

    @Test
    void 한도는_설정값을_따른다() {
        MatchRateLimiter custom = new MatchRateLimiter(3, clock);
        assertEquals(3, custom.maxPerWindow());
        for (int i = 0; i < 3; i++) {
            assertTrue(custom.tryAcquire("203.0.113.9"));
        }
        assertFalse(custom.tryAcquire("203.0.113.9"));
    }
}
