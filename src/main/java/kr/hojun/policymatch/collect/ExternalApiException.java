package kr.hojun.policymatch.collect;

/**
 * 외부 API(출처) 호출 · 응답이 잘못됐을 때 던지는 예외. 어느 출처에서 났는지 함께 담는다.
 *
 * 우리 서버의 버그가 아니라 외부 의존의 실패이므로, 관리 API 는 502 와 "{출처}_API_FAILURE" 로 돌려준다.
 * 온통청년 쪽은 하위 클래스 {@link YouthApiException} 을 그대로 쓴다(응답 글자 YOUTH_API_FAILURE 유지).
 */
public class ExternalApiException extends RuntimeException {

    private final Source source;

    public ExternalApiException(Source source, String message) {
        super(message);
        this.source = source;
    }

    public ExternalApiException(Source source, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    public Source source() {
        return source;
    }
}
