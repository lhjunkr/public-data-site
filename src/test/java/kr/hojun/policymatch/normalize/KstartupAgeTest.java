package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** K-Startup 대상 연령 3구간 → 나이 범위 (12/10). 값은 12/5 실측 175건에 나온 글 그대로다. */
class KstartupAgeTest {

    private static final String UNDER_20 = "만 20세 미만";
    private static final String FROM_20_TO_39 = "만 20세 이상 ~ 만 39세 이하";
    private static final String FROM_40 = "만 40세 이상";

    @Test
    void 세_구간_모두면_나이_무관() {
        // 115건
        KstartupAge age = KstartupAge.parse(UNDER_20 + "," + FROM_20_TO_39 + "," + FROM_40);

        assertNull(age.min());
        assertNull(age.max());
        assertFalse(age.limited());
        assertTrue(age.recognized());
    }

    @Test
    void 이십대_삼십대와_사십대_이상이면_20세_이상() {
        // 36건
        assertRange(20, null, KstartupAge.parse(FROM_20_TO_39 + "," + FROM_40));
    }

    @Test
    void 이십대_삼십대만이면_20세부터_39세() {
        // 18건
        assertRange(20, 39, KstartupAge.parse(FROM_20_TO_39));
    }

    @Test
    void 이십_미만과_이십대_삼십대면_39세_이하() {
        // 4건
        assertRange(null, 39, KstartupAge.parse(UNDER_20 + "," + FROM_20_TO_39));
    }

    @Test
    void 한_구간만이면_그_구간() {
        assertRange(40, null, KstartupAge.parse(FROM_40));
        assertRange(null, 19, KstartupAge.parse(UNDER_20));
    }

    @Test
    void 가운데가_빈_두_구간은_나이_무관으로_넓힌다() {
        // 범위 하나로 나타낼 수 없다. 빼는 것보다 넓게 보여 주는 편이 낫다(D-001)
        KstartupAge age = KstartupAge.parse(UNDER_20 + "," + FROM_40);

        assertFalse(age.limited());
        assertTrue(age.recognized());
    }

    @Test
    void 빈_값과_콤마_뒤_공백도_읽는다() {
        assertFalse(KstartupAge.parse(null).limited());
        assertFalse(KstartupAge.parse("  ").limited());
        assertRange(20, null, KstartupAge.parse(FROM_20_TO_39 + ", " + FROM_40));
    }

    @Test
    void 모르는_글이_섞이면_나이_무관으로_두고_알린다() {
        KstartupAge age = KstartupAge.parse(FROM_20_TO_39 + ",만 65세 이상");

        assertFalse(age.limited());
        assertFalse(age.recognized(), "정규화가 로그에 남길 수 있게 표시한다");
    }

    private static void assertRange(Integer min, Integer max, KstartupAge age) {
        assertEquals(min, age.min());
        assertEquals(max, age.max());
        assertTrue(age.recognized());
    }
}
