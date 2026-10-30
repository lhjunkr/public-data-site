package kr.hojun.policymatch.match;

import java.util.List;

/**
 * 매칭 결과 한 건.
 *
 * @param incomeNote 소득 조건이 문장으로만 있을 때 그 원문 (0043003). 화면에 그대로 보여준다.
 * @param needsCheck 추가 자격 조건 원문이 있으면 true → "확인 필요" 뱃지 (D-003)
 * @param categories 이 정책이 속한 열린 칩 코드 ("탭.칩", 예: housing.rent). 이름은 GET /api/v1/categories (D-018)
 */
public record MatchedPolicy(
        long id,
        String title,
        String categoryLarge,
        String categoryMedium,
        String supportContent,
        String applyPeriodText,
        String applyUrl,
        String refUrl,
        IncomeStatus incomeStatus,
        String incomeNote,
        boolean needsCheck,
        List<String> categories) {

    public MatchedPolicy withCategories(List<String> chips) {
        return new MatchedPolicy(id, title, categoryLarge, categoryMedium, supportContent, applyPeriodText,
                applyUrl, refUrl, incomeStatus, incomeNote, needsCheck, List.copyOf(chips));
    }
}
