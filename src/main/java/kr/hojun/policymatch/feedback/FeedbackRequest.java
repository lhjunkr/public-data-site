package kr.hojun.policymatch.feedback;

/**
 * "의견 보내기" 요청 본문 (D-024).
 *
 * @param message 의견 본문 (필수, 1~1,000자)
 * @param email   답장받을 이메일 (선택)
 * @param consent 이메일 수집 · 이용 동의 여부. 이메일을 적었다면 true 여야 한다
 * @param website 화면에 보이지 않는 칸. 사람은 비워 두고, 칸을 모두 채우는 자동 입력 프로그램만 값을 넣는다
 */
public record FeedbackRequest(String message, String email, Boolean consent, String website) {}
