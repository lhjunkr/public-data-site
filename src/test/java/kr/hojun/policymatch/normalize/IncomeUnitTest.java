package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * D-016 소득 금액 단위 통일 — 스프링 없이 도는 단위 테스트.
 * 10만 이상은 원 단위로 보고 만원으로 바꾼다(올림). 그보다 작으면 이미 만원 단위다.
 */
class IncomeUnitTest {

    @Test
    void 만원_단위_값은_그대로_둔다() {
        assertEquals(Long.valueOf(0), PolicyNormalizer.toManwon(0L));
        assertEquals(Long.valueOf(5000), PolicyNormalizer.toManwon(5000L));
        assertEquals(Long.valueOf(99_999), PolicyNormalizer.toManwon(99_999L));
    }

    @Test
    void 원_단위_값은_만원으로_올림해_바꾼다() {
        assertEquals(Long.valueOf(10), PolicyNormalizer.toManwon(100_000L));
        assertEquals(Long.valueOf(4306), PolicyNormalizer.toManwon(43_056_240L));
        assertEquals(Long.valueOf(5000), PolicyNormalizer.toManwon(50_000_000L));
    }

    @Test
    void 값이_없으면_없는_그대로() {
        assertNull(PolicyNormalizer.toManwon(null));
    }
}
