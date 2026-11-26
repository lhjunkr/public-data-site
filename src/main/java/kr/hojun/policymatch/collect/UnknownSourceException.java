package kr.hojun.policymatch.collect;

/** 없는 출처이거나, 출처는 있지만 아직 수집기를 붙이지 않았을 때. 관리 API 는 400 으로 돌려준다. */
public class UnknownSourceException extends RuntimeException {

    public UnknownSourceException(String message) {
        super(message);
    }
}
