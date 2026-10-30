package kr.hojun.policymatch.category;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 화면에 보일 분류 탭과 칩 목록 (D-018). 닫힌 탭(일자리)은 내보내지 않는다. */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryCatalog catalog;

    public CategoryController(CategoryCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<TabResponse> list() {
        return catalog.openTabs().stream()
                .map(tab -> new TabResponse(tab.code(), tab.name(),
                        tab.chips().stream()
                                .map(chip -> new ChipResponse(tab.code() + "." + chip.code(), chip.name()))
                                .toList()))
                .toList();
    }

    public record TabResponse(String code, String name, List<ChipResponse> chips) {}

    /** @param code "탭.칩" 형태. 매칭 결과의 categories 값과 같다 */
    public record ChipResponse(String code, String name) {}
}
