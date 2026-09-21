package kr.hojun.policymatch.collect;

/** 온통청년 API 호출이 실패했을 때 던지는 예외. */
public class YouthApiException extends RuntimeException {

    public YouthApiException(String message) {
        super(message);
    }

    public YouthApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
