package kr.hojun.policymatch.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 같은 접속 주소(IP)에서 정해진 시간(window) 안에 정해진 횟수(maxPerWindow)까지만 받는다.
 * 의견 보내기(D-024)와 정책 상세 조회(D-027)가 횟수 · 시간만 바꿔 함께 쓴다.
 *
 * IP 는 메모리에만 잠깐 두고 DB 에는 저장하지 않는다. 시간이 지난 기록은 다음 요청 때 지운다.
 * 앱이 한 대라 앱 안 메모리로 충분하다(D-020 과 같은 판단). 앱이 다시 뜨면 기록은 사라진다.
 */
public class SlidingWindowRateLimiter {

    private final int maxPerWindow;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new HashMap<>();

    protected SlidingWindowRateLimiter(int maxPerWindow, Duration window, Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.window = window;
        this.clock = clock;
    }

    /** 이번 요청을 받아도 되면 true (그리고 1번으로 센다), 한도를 넘었으면 false */
    public synchronized boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Instant windowStart = now.minus(window);
        for (Deque<Instant> q : hits.values()) {
            while (!q.isEmpty() && !q.peekFirst().isAfter(windowStart)) {
                q.pollFirst();
            }
        }
        hits.values().removeIf(Deque::isEmpty);

        Deque<Instant> mine = hits.computeIfAbsent(key == null ? "unknown" : key, k -> new ArrayDeque<>());
        if (mine.size() >= maxPerWindow) {
            return false;
        }
        mine.addLast(now);
        return true;
    }
}
