package kr.hojun.policymatch.normalize;

import java.time.LocalDate;

/**
 * policy 표 한 행에 넣을 값 (12/10). 출처별 정규화기가 원본을 이 모양으로 바꾸고, PolicyStore 가 그대로 넣는다.
 * 칸 이름은 policy 표와 같다. 출처에 없는 값은 null 로 둔다(D-029 ③: 나이 · 소득이 없으면 비움).
 */
public record PolicyRow(
        String policyNo,
        String title,
        String categoryLarge,
        String categoryMedium,
        Integer minAge,
        Integer maxAge,
        String ageLimitYn,
        String earnCndSeCd,
        Long earnMinAmt,
        Long earnMaxAmt,
        String earnEtcCn,
        String description,
        String supportContent,
        String applyMethod,
        String requiredDocuments,
        String extraQualification,
        String etcMatters,
        String applyPeriodText,
        LocalDate applyStartDate,
        LocalDate applyEndDate,
        String applyUrl,
        String refUrl,
        String supervisingOrg,
        Integer viewCount) {}
