package kr.hojun.policymatch.match;

import java.time.Clock;
import java.time.Duration;
import kr.hojun.policymatch.ratelimit.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 같은 접속 주소(IP)에서 1분에 정해진 횟수까지만 매칭(검색)을 받는다 (12/3).
 *
 * 매칭 1건은 DB 서버 CPU 를 가장 많이 쓰는 요청이라(D-027: 한계는 DB CPU),
 * 한 곳에서 반복해서 부르면 다른 사용자까지 느려진다. 사람이 조건을 바꿔 가며 검색하는 속도
 * (몇 초에 1번)로는 닿지 않는 한도로 잡았다. 기본 1분 20번 = 3초에 1번꼴이 계속 이어지는 속도.
 *
 * 한도는 설정(ratelimit.match.per-minute)으로 바꿀 수 있다. 부하 측정(k6, 한 IP 에서 많이 보냄)
 * 때는 이 값을 올려야 측정이 429 에 막히지 않는다.
 */
@Component
public class MatchRateLimiter extends SlidingWindowRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxPerWindow;

    @Autowired
    public MatchRateLimiter(@Value("${ratelimit.match.per-minute:20}") int maxPerMinute) {
        this(maxPerMinute, Clock.systemUTC());
    }

    MatchRateLimiter(int maxPerMinute, Clock clock) {
        super(maxPerMinute, WINDOW, clock);
        this.maxPerWindow = maxPerMinute;
    }

    /** 1분에 받는 최대 횟수. 429 안내 문구와 테스트가 쓴다 */
    public int maxPerWindow() {
        return maxPerWindow;
    }
}
