package kr.hojun.policymatch.feedback;

/**
 * 관리용 목록의 의견 한 건.
 *
 * @param createdAt 받은 시각(한국 시간, "yyyy-MM-dd HH:mm"). DB 에서 글자로 꺼낸다 (D-023 의 시간대 교훈)
 * @param email     답장용 이메일. 없거나 이미 지웠으면 null
 */
public record FeedbackItem(long id, String createdAt, String message, String email, String status) {}
