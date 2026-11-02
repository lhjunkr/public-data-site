package kr.hojun.policymatch.feedback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 같은 접속 주소(IP)에서 10분에 3번까지만 의견을 받는다 (D-024, 도배 방지).
 *
 * IP 는 메모리에만 잠깐 두고 DB 에는 저장하지 않는다. 10분이 지난 기록은 다음 요청 때 지운다.
 * 앱이 한 대라 앱 안 메모리로 충분하다(D-020 과 같은 판단). 앱이 다시 뜨면 기록은 사라진다.
 */
@Component
public class FeedbackRateLimiter {

    static final int MAX_PER_WINDOW = 3;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new HashMap<>();

    public FeedbackRateLimiter() {
        this(Clock.systemUTC());
    }

    /** 테스트에서 시계를 바꿔 끼우기 위한 생성자 */
    FeedbackRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 이번 요청을 받아도 되면 true (그리고 1번으로 센다), 한도를 넘었으면 false */
    public synchronized boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Instant windowStart = now.minus(WINDOW);
        for (Deque<Instant> q : hits.values()) {
            while (!q.isEmpty() && !q.peekFirst().isAfter(windowStart)) {
                q.pollFirst();
            }
        }
        hits.values().removeIf(Deque::isEmpty);

        Deque<Instant> mine = hits.computeIfAbsent(key == null ? "unknown" : key, k -> new ArrayDeque<>());
        if (mine.size() >= MAX_PER_WINDOW) {
            return false;
        }
        mine.addLast(now);
        return true;
    }
}
