package kr.hojun.policymatch.batch;

/** 다른 수집 · 정규화가 실행 중이라 이번 요청을 거절했다 (D-020). 관리용 엔드포인트에서는 409 로 응답한다. */
public class BatchBusyException extends RuntimeException {

    public BatchBusyException(String runningJob) {
        super("이미 실행 중인 작업이 있습니다: " + (runningJob == null ? "다른 작업" : runningJob)
                + ". 끝난 뒤 다시 실행하십시오.");
    }
}
