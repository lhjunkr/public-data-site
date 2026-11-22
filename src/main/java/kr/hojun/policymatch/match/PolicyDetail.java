package kr.hojun.policymatch.match;

/**
 * 정책 상세 한 건 (D-027). 목록(매칭)에서 뺀 긴 글을 정책 하나씩만 내준다.
 *
 * @param supportContent 지원 내용 원문. 화면의 "자세히"를 누르면 보여 준다
 */
public record PolicyDetail(long id, String title, String supportContent) {}
