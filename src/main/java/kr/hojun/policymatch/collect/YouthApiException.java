package kr.hojun.policymatch.collect;

/** 온통청년 API 호출이 실패했을 때 던지는 예외. */
public class YouthApiException extends ExternalApiException {

    public YouthApiException(String message) {
        super(Source.YOUTH, message);
    }

    public YouthApiException(String message, Throwable cause) {
        super(Source.YOUTH, message, cause);
    }
}
