package kr.hojun.policymatch.match;

import java.util.List;

/**
 * 매칭 결과 한 건. 지원 내용 같은 긴 글은 담지 않는다 — 상세 조회 PolicyDetail (D-027).
 *
 * @param incomeNote 소득 조건이 문장으로만 있을 때 그 원문 (0043003). 화면에 그대로 보여준다.
 * @param needsCheck 추가 자격 조건 원문이 있으면 true → "확인 필요" 뱃지 (D-003). SQL 에서 계산한다(D-027)
 * @param categories 이 정책이 속한 열린 칩 코드 ("탭.칩", 예: housing.rent). 이름은 GET /api/v1/categories (D-018)
 * @param source     출처 코드 (youth · kstartup, D-029). 화면은 이것으로 출처를 적고, 소득 칸이 없는 출처는 소득 표시를 숨긴다
 */
public record MatchedPolicy(
        long id,
        String title,
        String categoryLarge,
        String categoryMedium,
        String applyPeriodText,
        String applyUrl,
        String refUrl,
        IncomeStatus incomeStatus,
        String incomeNote,
        boolean needsCheck,
        List<String> categories,
        String source) {

    public MatchedPolicy withCategories(List<String> chips) {
        return new MatchedPolicy(id, title, categoryLarge, categoryMedium, applyPeriodText,
                applyUrl, refUrl, incomeStatus, incomeNote, needsCheck, List.copyOf(chips), source);
    }
}
