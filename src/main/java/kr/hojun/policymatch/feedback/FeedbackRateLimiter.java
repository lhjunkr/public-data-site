package kr.hojun.policymatch.feedback;

import java.time.Clock;
import java.time.Duration;
import kr.hojun.policymatch.ratelimit.SlidingWindowRateLimiter;
import org.springframework.stereotype.Component;

/**
 * 같은 접속 주소(IP)에서 10분에 3번까지만 의견을 받는다 (D-024, 도배 방지).
 * 세는 방법은 SlidingWindowRateLimiter 에 있다(정책 상세 조회와 공유).
 */
@Component
public class FeedbackRateLimiter extends SlidingWindowRateLimiter {

    static final int MAX_PER_WINDOW = 3;
    static final Duration WINDOW = Duration.ofMinutes(10);

    public FeedbackRateLimiter() {
        this(Clock.systemUTC());
    }

    /** 테스트에서 시계를 바꿔 끼우기 위한 생성자 */
    FeedbackRateLimiter(Clock clock) {
        super(MAX_PER_WINDOW, WINDOW, clock);
    }
}
