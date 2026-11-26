package kr.hojun.policymatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.PolicyCollector;
import kr.hojun.policymatch.collect.Source;
import kr.hojun.policymatch.collect.SourceCollector;
import kr.hojun.policymatch.collect.UnknownSourceException;
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
        PolicyBatch batch = new PolicyBatch(List.of(new FakeCollector(() -> COLLECTED)), normalizer);

        RefreshResult result = batch.refresh();

        assertEquals(3763, result.collect().itemsSeen());
        assertEquals(1, normalizer.calls.get());
    }

    @Test
    void 수집이_실패하면_정규화하지_않는다() {
        // 10/30 사고: 수집이 9페이지에서 실패했는데 정규화를 돌려 정상 정책이 비활성됐다
        FakeNormalizer normalizer = new FakeNormalizer();
        PolicyBatch batch = new PolicyBatch(List.of(new FakeCollector(() -> {
            throw new YouthApiException("9페이지 400");
        })), normalizer);

        assertThrows(YouthApiException.class, batch::refresh);
        assertEquals(0, normalizer.calls.get());
    }

    @Test
    void 실행_중에는_다른_작업을_거절한다() throws Exception {
        CountDownLatch collecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeNormalizer normalizer = new FakeNormalizer();
        FakeSourceCollector kstartup = new FakeSourceCollector(Source.KSTARTUP);
        PolicyBatch batch = new PolicyBatch(List.of(new FakeCollector(() -> {
            collecting.countDown();
            await(release);
            return COLLECTED;
        }), kstartup), normalizer);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RefreshResult> daily = pool.submit(batch::refresh);
            assertTrue(collecting.await(5, TimeUnit.SECONDS), "자동 갱신이 수집 중인 상태");

            BatchBusyException busy = assertThrows(BatchBusyException.class, batch::normalize);
            assertTrue(busy.getMessage().contains("수집+정규화"), "무엇이 돌고 있는지 알려 준다");
            assertThrows(BatchBusyException.class, batch::collect);
            assertThrows(BatchBusyException.class, () -> batch.collect(Source.KSTARTUP), "출처가 달라도 한 번에 하나");
            assertEquals(0, kstartup.calls.get());

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
        PolicyBatch batch = new PolicyBatch(List.of(new FakeCollector(() -> {
            throw new YouthApiException("API 응답 없음");
        })), normalizer);

        assertThrows(YouthApiException.class, batch::collect);

        batch.normalize();
        assertEquals(1, normalizer.calls.get(), "실패 뒤에도 다음 작업이 실행된다");
    }

    @Test
    void 강제_정규화는_상한을_보지_않는_경로로만_간다() {
        // D-019 "12/8 추가": force=true 일 때만 상한 없이 반영한다. 기본 정규화 · 수집+정규화는 상한을 본다
        FakeNormalizer normalizer = new FakeNormalizer();
        PolicyBatch batch = new PolicyBatch(List.of(new FakeCollector(() -> COLLECTED)), normalizer);

        batch.normalize(true);
        batch.normalize();
        batch.refresh();

        assertEquals(1, normalizer.forcedCalls.get());
        assertEquals(2, normalizer.calls.get());
    }

    @Test
    void 출처를_고르면_그_출처의_수집기만_돈다() {
        // 12/9: K-Startup 수집은 원본만 받는다. 온통청년 수집 · 정규화는 돌지 않는다
        FakeNormalizer normalizer = new FakeNormalizer();
        AtomicInteger youthCalls = new AtomicInteger();
        FakeSourceCollector kstartup = new FakeSourceCollector(Source.KSTARTUP);
        PolicyBatch batch = new PolicyBatch(List.of(new FakeCollector(() -> {
            youthCalls.incrementAndGet();
            return COLLECTED;
        }), kstartup), normalizer);

        CollectResult result = batch.collect(Source.KSTARTUP);

        assertEquals(175, result.itemsSeen());
        assertEquals(1, kstartup.calls.get());
        assertEquals(0, youthCalls.get());
        assertEquals(0, normalizer.calls.get());
    }

    @Test
    void 수집기가_없는_출처는_거절한다() {
        PolicyBatch batch = new PolicyBatch(List.of(new FakeCollector(() -> COLLECTED)), new FakeNormalizer());

        assertThrows(UnknownSourceException.class, () -> batch.collect(Source.KSTARTUP));
    }

    @Test
    void 같은_출처의_수집기가_둘이면_앱이_뜨지_않는다() {
        // 어느 쪽이 불릴지 모르는 상태로 운영되느니 시작할 때 실패시킨다
        List<SourceCollector> twice = List.of(new FakeCollector(() -> COLLECTED),
                new FakeSourceCollector(Source.KSTARTUP), new FakeSourceCollector(Source.KSTARTUP));

        assertThrows(IllegalStateException.class, () -> new PolicyBatch(twice, new FakeNormalizer()));
    }

    @Test
    void 온통청년_수집기가_없으면_앱이_뜨지_않는다() {
        // 매일 갱신(refresh)은 온통청년 수집기를 쓴다
        assertThrows(IllegalStateException.class,
                () -> new PolicyBatch(List.of(new FakeSourceCollector(Source.KSTARTUP)), new FakeNormalizer()));
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

    /** 온통청년이 아닌 출처의 가짜 수집기. 불린 횟수만 센다. */
    private static class FakeSourceCollector implements SourceCollector {
        final AtomicInteger calls = new AtomicInteger();
        private final Source source;

        FakeSourceCollector(Source source) {
            this.source = source;
        }

        @Override
        public Source source() {
            return source;
        }

        @Override
        public CollectResult collectAll() {
            calls.incrementAndGet();
            return new CollectResult(175, 2, 175, 175);
        }
    }

    /** DB 를 건드리지 않고 불린 횟수만 센다. */
    private static class FakeNormalizer extends PolicyNormalizer {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger forcedCalls = new AtomicInteger();

        FakeNormalizer() {
            super(null, null, null, null);
        }

        @Override
        public NormalizeResult normalize() {
            calls.incrementAndGet();
            return new NormalizeResult(3763, 3763, 0, 0, 0, 0, 0, 0, 0);
        }

        @Override
        public NormalizeResult normalizeAllowingMassDeactivation() {
            forcedCalls.incrementAndGet();
            return new NormalizeResult(3763, 3763, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
