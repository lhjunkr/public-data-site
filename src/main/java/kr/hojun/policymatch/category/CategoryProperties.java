package kr.hojun.policymatch.category;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 화면 분류 묶음표 (D-018). application.yml 의 category.tabs 를 그대로 담는다.
 * 탭(대분류) → 칩(세부) → 원본 중분류 이름. 묶음을 바꿀 때 코드를 고치지 않는다(D-011).
 */
@ConfigurationProperties(prefix = "category")
public record CategoryProperties(List<Tab> tabs) {

    /** @param open false 이면 화면에 보이지 않고, 이 탭의 칩만 가진 정책은 결과에 나오지 않는다 */
    public record Tab(String code, String name, boolean open, List<Chip> chips) {}

    /** @param mediums 이 칩에 넣을 원본 중분류 이름들 */
    public record Chip(String code, String name, List<String> mediums) {}
}
