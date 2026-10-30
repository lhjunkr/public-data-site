package kr.hojun.policymatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.PolicyCollector;
import kr.hojun.policymatch.collect.YouthApiException;
import kr.hojun.policymatch.normalize.NormalizeResult;
import kr.hojun.policymatch.normalize.PolicyNormalizer;
import org.junit.jupiter.api.Test;

/**
 * 매일 갱신의 순서와 동시 실행 방지 (D-020).
 * DB 와 외부 API 없이, 정해진 대로 움직이는 가짜 수집기 · 정규화기로 PolicyBatch 의 규칙만 확인한다.
 */
class PolicyBatchTest {

    private static final CollectResult COLLECTED = new CollectResult(3763, 38, 3763, 3763);

    @Test
    void 수집이_성공하면_이어서_정규화한다() {
        FakeNormalizer normalizer = new FakeNormalizer();
        PolicyBatch batch = new PolicyBatch(new FakeCollector(() -> COLLECTED), normalizer);

        RefreshResult result = batch.refresh();

        assertEquals(3763, result.collect().itemsSeen());
        assertEquals(1, normalizer.calls.get());
    }

    @Test
    void 수집이_실패하면_정규화하지_않는다() {
        // 10/30 사고: 수집이 9페이지에서 실패했는데 정규화를 돌려 정상 정책이 비활성됐다
        FakeNormalizer normalizer = new FakeNormalizer();
        PolicyBatch batch = new PolicyBatch(new FakeCollector(() -> {
            throw new YouthApiException("9페이지 400");
        }), normalizer);

        assertThrows(YouthApiException.class, batch::refresh);
        assertEquals(0, normalizer.calls.get());
    }

    @Test
    void 실행_중에는_다른_작업을_거절한다() throws Exception {
        CountDownLatch collecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeNormalizer normalizer = new FakeNormalizer();
        PolicyBatch batch = new PolicyBatch(new FakeCollector(() -> {
            collecting.countDown();
            await(release);
            return COLLECTED;
        }), normalizer);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RefreshResult> daily = pool.submit(batch::refresh);
            assertTrue(collecting.await(5, TimeUnit.SECONDS), "자동 갱신이 수집 중인 상태");

            BatchBusyException busy = assertThrows(BatchBusyException.class, batch::normalize);
            assertTrue(busy.getMessage().contains("수집+정규화"), "무엇이 돌고 있는지 알려 준다");
            assertThrows(BatchBusyException.class, batch::collect);

            release.countDown();
            daily.get(5, TimeUnit.SECONDS);
            assertEquals(1, normalizer.calls.get(), "자동 갱신의 정규화 1번만 돌았다");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 작업이_실패해도_잠금이_풀린다() {
        FakeNormalizer normalizer = new FakeNormalizer();
        PolicyBatch batch = new PolicyBatch(new FakeCollector(() -> {
            throw new YouthApiException("API 응답 없음");
        }), normalizer);

        assertThrows(YouthApiException.class, batch::collect);

        batch.normalize();
        assertEquals(1, normalizer.calls.get(), "실패 뒤에도 다음 작업이 실행된다");
    }

    // ---------- 가짜 ----------

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 실제 API 를 부르지 않고 정해진 결과를 돌려주거나 예외를 던진다. */
    private static class FakeCollector extends PolicyCollector {
        private final Supplier<CollectResult> behavior;

        FakeCollector(Supplier<CollectResult> behavior) {
            super(null, null, null, null);
            this.behavior = behavior;
        }

        @Override
        public CollectResult collectAll() {
            return behavior.get();
        }
    }

    /** DB 를 건드리지 않고 불린 횟수만 센다. */
    private static class FakeNormalizer extends PolicyNormalizer {
        final AtomicInteger calls = new AtomicInteger();

        FakeNormalizer() {
            super(null, null, null, null);
        }

        @Override
        public NormalizeResult normalize() {
            calls.incrementAndGet();
            return new NormalizeResult(3763, 3763, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
