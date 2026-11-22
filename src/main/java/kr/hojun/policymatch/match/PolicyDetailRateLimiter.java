package kr.hojun.policymatch.match;

import java.time.Clock;
import java.time.Duration;
import kr.hojun.policymatch.ratelimit.SlidingWindowRateLimiter;
import org.springframework.stereotype.Component;

/**
 * 같은 접속 주소(IP)에서 1분에 30번까지만 정책 상세를 내준다 (D-027).
 * 상세 조회는 정책을 하나씩 내주는 새 입구라, 제한이 없으면 id 를 1 부터 차례로 불러
 * 전체를 통째로 가져갈 수 있다(D-023). 사람이 "자세히"를 누르는 속도로는 닿지 않는 한도로 잡았다.
 */
@Component
public class PolicyDetailRateLimiter extends SlidingWindowRateLimiter {

    static final int MAX_PER_WINDOW = 30;
    static final Duration WINDOW = Duration.ofMinutes(1);

    public PolicyDetailRateLimiter() {
        this(Clock.systemUTC());
    }

    PolicyDetailRateLimiter(Clock clock) {
        super(MAX_PER_WINDOW, WINDOW, clock);
    }
}
