package kr.hojun.policymatch.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import kr.hojun.policymatch.category.CategoryProperties.Chip;
import kr.hojun.policymatch.category.CategoryProperties.Tab;
import org.junit.jupiter.api.Test;

/** 묶음표 해석 단위 테스트 (D-018). 스프링 없이 묶음표를 직접 만들어 시험한다. */
class CategoryCatalogTest {

    private final CategoryCatalog catalog = new CategoryCatalog(new CategoryProperties(List.of(
            new Tab("housing", "주거", true, List.of(
                    new Chip("rent", "전월세", List.of("전월세 및 주거급여 지원")),
                    new Chip("house", "주택", List.of("주택 및 거주지", "기숙사")))),
            new Tab("education", "교육", true, List.of(
                    new Chip("skills", "역량개발·강의", List.of("미래역량강화", "온·오프라인교육")))),
            new Tab("jobs", "일자리", false, List.of(
                    new Chip("employment", "취업", List.of("취업")))))));

    @Test
    void 콤마로_붙은_중분류를_각각_칩으로_바꾼다() {
        assertEquals(List.of("jobs.employment", "housing.rent"),
                List.copyOf(catalog.chipsOf("취업,전월세 및 주거급여 지원")));
    }

    @Test
    void 같은_칩의_중분류가_여러_개여도_칩은_한_번만() {
        assertEquals(List.of("housing.house"), List.copyOf(catalog.chipsOf("주택 및 거주지,기숙사")));
    }

    @Test
    void 가운뎃점_종류와_공백_차이는_같은_이름으로_본다() {
        // 원본에는 · (U+00B7) 와 ･ (U+FF65) 가 섞여 있다
        assertEquals(List.of("education.skills"), List.copyOf(catalog.chipsOf("온･오프라인교육")));
        assertEquals(List.of("housing.house"), List.copyOf(catalog.chipsOf("주택  및 거주지")));
    }

    @Test
    void 묶음표에_없는_중분류는_칩이_없고_따로_알려준다() {
        assertTrue(catalog.chipsOf("새로운분류").isEmpty());
        assertTrue(catalog.chipsOf(null).isEmpty());
        assertTrue(catalog.chipsOf("").isEmpty());
        assertEquals(List.of("새로운분류"), catalog.unmappedOf("취업,새로운분류"));
    }

    @Test
    void 열린_탭의_칩만_공개한다() {
        assertEquals(List.of("housing.rent", "housing.house", "education.skills"), catalog.openChipCodes());
        assertEquals(List.of("housing", "education"), catalog.openTabs().stream().map(Tab::code).toList());
    }

    @Test
    void 같은_중분류를_두_칩에_넣으면_시작할_때_실패한다() {
        CategoryProperties duplicated = new CategoryProperties(List.of(
                new Tab("a", "가", true, List.of(new Chip("x", "엑스", List.of("취업")))),
                new Tab("b", "나", true, List.of(new Chip("y", "와이", List.of("취업"))))));

        assertThrows(IllegalStateException.class, () -> new CategoryCatalog(duplicated));
    }

    @Test
    void 열린_탭이_없으면_시작할_때_실패한다() {
        CategoryProperties allClosed = new CategoryProperties(List.of(
                new Tab("a", "가", false, List.of(new Chip("x", "엑스", List.of("취업"))))));

        assertThrows(IllegalStateException.class, () -> new CategoryCatalog(allClosed));
    }
}
