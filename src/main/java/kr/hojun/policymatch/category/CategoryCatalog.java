package kr.hojun.policymatch.category;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 묶음표(CategoryProperties)를 조회하기 쉬운 형태로 바꿔 들고 있는다 (D-018).
 *
 *  - 칩 코드는 "탭.칩" 형태다. 예: housing.rent
 *  - 원본 중분류 이름을 비교할 때 공백과 가운뎃점(·, ･, ・, ‧)을 무시한다.
 *    원본에 두 종류의 가운뎃점이 섞여 있어 글자 그대로 비교하면 같은 이름을 놓친다.
 *  - 묶음표가 잘못되면(같은 중분류를 두 칩에 넣음, 칩 코드 중복, 열린 탭 없음) 앱이 뜰 때 바로 실패한다.
 *    잘못된 설정으로 조용히 정책이 사라지는 것보다 배포가 실패하는 편이 안전하다.
 */
@Component
public class CategoryCatalog {

    private final List<CategoryProperties.Tab> openTabs;
    private final List<String> openChipCodes;
    private final Map<String, String> chipByMedium;

    public CategoryCatalog(CategoryProperties props) {
        if (props == null || props.tabs() == null || props.tabs().isEmpty()) {
            throw new IllegalStateException("category.tabs 가 비어 있습니다.");
        }
        Map<String, String> byMedium = new HashMap<>();
        Set<String> chipCodes = new HashSet<>();
        List<CategoryProperties.Tab> open = new ArrayList<>();
        List<String> openChips = new ArrayList<>();

        for (CategoryProperties.Tab tab : props.tabs()) {
            for (CategoryProperties.Chip chip : tab.chips()) {
                String code = tab.code() + "." + chip.code();
                if (!chipCodes.add(code)) {
                    throw new IllegalStateException("칩 코드가 중복됐습니다: " + code);
                }
                for (String medium : chip.mediums()) {
                    String previous = byMedium.put(key(medium), code);
                    if (previous != null) {
                        throw new IllegalStateException(
                                "중분류 '" + medium + "' 이(가) 두 칩에 들어 있습니다: " + previous + ", " + code);
                    }
                }
                if (tab.open()) {
                    openChips.add(code);
                }
            }
            if (tab.open()) {
                open.add(tab);
            }
        }
        if (open.isEmpty()) {
            throw new IllegalStateException("열린 탭(open: true)이 하나도 없습니다.");
        }
        this.openTabs = List.copyOf(open);
        this.openChipCodes = List.copyOf(openChips);
        this.chipByMedium = Map.copyOf(byMedium);
    }

    /** 원본 중분류 값(콤마 다중값 가능)을 칩 코드 집합으로 바꾼다. 묶음표에 없는 이름은 건너뛴다. */
    public Set<String> chipsOf(String mediumCsv) {
        Set<String> chips = new LinkedHashSet<>();
        for (String medium : split(mediumCsv)) {
            String chip = chipByMedium.get(key(medium));
            if (chip != null) {
                chips.add(chip);
            }
        }
        return chips;
    }

    /** 원본 중분류 값 중 묶음표에 없는 이름. 정규화 로그에 남겨 새 분류가 생겼는지 알아챈다. */
    public List<String> unmappedOf(String mediumCsv) {
        List<String> unmapped = new ArrayList<>();
        for (String medium : split(mediumCsv)) {
            if (!chipByMedium.containsKey(key(medium))) {
                unmapped.add(medium);
            }
        }
        return unmapped;
    }

    /** 열린 탭에 속한 칩 코드 (설정 순서). 매칭 결과는 이 칩을 하나라도 가진 정책만 낸다. */
    public List<String> openChipCodes() {
        return openChipCodes;
    }

    /** 화면에 보일 탭 (설정 순서) */
    public List<CategoryProperties.Tab> openTabs() {
        return openTabs;
    }

    static String key(String medium) {
        return medium == null ? "" : medium.replaceAll("[\\s·･・‧]", "");
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
