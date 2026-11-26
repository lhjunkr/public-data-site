package kr.hojun.policymatch.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 시도 이름 → 시도 코드 (D-029 ⑥, 12/10). 실제 설정(application.yml)을 읽어 확인한다. */
@SpringBootTest
class SidoNamesTest {

    @Autowired SidoNames sidoNames;

    @Test
    void 약칭과_정식_이름과_통합_전_이름이_같은_코드가_된다() {
        assertEquals(Set.of("11"), sidoNames.codesOf("서울"));
        assertEquals(Set.of("11"), sidoNames.codesOf("서울특별시"));
        // K-Startup 은 "전남광주", 마이홈은 정식 이름, 잡알리오는 통합 전 이름을 쓴다 (12/5)
        assertEquals(Set.of("12"), sidoNames.codesOf("전남광주"));
        assertEquals(Set.of("12"), sidoNames.codesOf("전남광주통합특별시"));
        assertEquals(Set.of("12"), sidoNames.codesOf("광주"));
        assertEquals(Set.of("12"), sidoNames.codesOf("전남"));
        assertEquals(Set.of("51"), sidoNames.codesOf("강원특별자치도"));
    }

    @Test
    void 공백은_무시한다() {
        assertEquals(Set.of("12"), sidoNames.codesOf(" 전남 광주 "));
    }

    @Test
    void 전국은_모든_시도로_펼친다() {
        Set<String> all = sidoNames.codesOf("전국");

        assertEquals(16, all.size());
        assertTrue(all.containsAll(Set.of("11", "12", "36", "50", "51", "52")));
    }

    @Test
    void 모르는_이름과_빈_값은_빈_집합() {
        assertTrue(sidoNames.codesOf("해외").isEmpty());
        assertTrue(sidoNames.codesOf(null).isEmpty());
        assertTrue(sidoNames.codesOf("").isEmpty());
    }

    @Test
    void 화면_이름은_첫_이름이다() {
        assertEquals("전남광주", sidoNames.displayName("12").orElseThrow());
        assertEquals("서울", sidoNames.displayName("11").orElseThrow());
        assertTrue(sidoNames.displayName("46").isEmpty(), "통합 전 옛 코드는 없다");
    }

    @Test
    void 같은_이름을_두_시도에_넣으면_앱이_뜨지_않는다() {
        // 잘못된 설정으로 공고가 엉뚱한 시도에 조용히 나오는 것보다 시작할 때 실패하는 편이 안전하다
        SidoProperties wrong = new SidoProperties(List.of(
                new SidoProperties.Region("11", List.of("서울")),
                new SidoProperties.Region("12", List.of("전남광주", "서울"))), List.of("전국"));

        assertThrows(IllegalStateException.class, () -> new SidoNames(wrong));
    }

    @Test
    void 시도_코드가_두_자리가_아니면_앱이_뜨지_않는다() {
        SidoProperties wrong = new SidoProperties(List.of(
                new SidoProperties.Region("1", List.of("서울"))), List.of("전국"));

        assertThrows(IllegalStateException.class, () -> new SidoNames(wrong));
    }
}
