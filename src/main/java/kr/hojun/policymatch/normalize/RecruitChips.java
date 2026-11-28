package kr.hojun.policymatch.normalize;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kr.hojun.policymatch.category.CategoryCatalog;

/**
 * 공공기관 채용 탭(application.yml 의 category.tabs 중 recruit)의 칩 규칙. 잡알리오(12/13) · 지방공공기관(12/14)이 같이 쓴다.
 *
 *  - 고용형태 칩: 원본 고용형태 이름 → 칩은 묶음표의 mediums 로 정한다. 출처마다 이름이 달라도(정규직 ↔ 일반정규직,
 *    비정규직 ↔ 기간제 · 일반계약직 · 전문계약직) 같은 칩으로 모은다. 새 이름은 설정에만 더한다(D-011).
 *  - 신입 가능 칩: 채용 구분에 '신입'이 있으면(신입 · 신입+경력) 붙인다. 원본 이름 매핑이 없다(mediums: []).
 */
final class RecruitChips {

    static final String TAB_PREFIX = "recruit.";
    static final String ENTRY_CHIP = "recruit.entry";
    static final String CATEGORY_LARGE = "공공기관 채용";

    private RecruitChips() {
    }

    /**
     * 앱이 뜰 때 확인한다: 신입 가능 칩이 있고, 측정된 고용형태 이름이 모두 공공기관 채용 탭의 칩에 들어 있는가.
     * 설정에서 칩이나 이름이 빠지면 공고가 조용히 분류 없이 들어가 화면에 안 나오므로, 배포가 실패하는 편이 낫다.
     * 탭을 닫아 둔 채 데이터부터 넣을 수도 있어(12/13 ~ 14) 열린 칩이 아니라 모든 칩에서 찾는다.
     */
    static void requireChips(CategoryCatalog categories, List<String> knownHireTypes, String sourceName) {
        if (!categories.allChipCodes().contains(ENTRY_CHIP)) {
            throw new IllegalStateException(sourceName + " 칩 " + ENTRY_CHIP + " 이(가) 묶음표에 없습니다. "
                    + "application.yml 의 category.tabs 를 확인하십시오.");
        }
        for (String hireType : knownHireTypes) {
            if (hireTypeChips(categories, hireType).isEmpty()) {
                throw new IllegalStateException(sourceName + " 고용형태 '" + hireType + "' 이(가) 공공기관 채용 탭의 칩에 없습니다. "
                        + "application.yml 의 category.tabs(recruit)를 확인하십시오.");
            }
        }
    }

    /** 고용형태 이름(콤마 여러 값)을 공공기관 채용 탭의 칩으로. 다른 탭의 칩은 붙이지 않는다(이름이 우연히 겹쳐도). */
    static Set<String> hireTypeChips(CategoryCatalog categories, String hireTypes) {
        Set<String> chips = new LinkedHashSet<>();
        for (String chip : categories.chipsOf(hireTypes)) {
            if (chip.startsWith(TAB_PREFIX)) {
                chips.add(chip);
            }
        }
        return chips;
    }

    /** 신입 · 신입+경력 → 신입 지원 가능. 경력만이면 아니다. */
    static boolean isEntryOpen(String recruitType) {
        return recruitType != null && recruitType.contains("신입");
    }

    /** "신입+경력" → "신입 + 경력" */
    static String recruitTypeText(String value) {
        return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s*\\+\\s*", " + ");
    }
}
