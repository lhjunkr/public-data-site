package kr.hojun.policymatch.match;

/**
 * 매칭 요청 본문.
 *
 * @param age    나이 (필수)
 * @param sido   시도 코드 2자리, 예: "11" 서울, "12" 전남광주 (필수)
 * @param income 연소득, 만원 단위 (선택. 없으면 금액비교 정책은 판단불가)
 */
public record MatchRequest(Integer age, String sido, Long income) {}
