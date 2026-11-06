package kr.hojun.policymatch.alert;

/**
 * 운영 알림 (D-026). 사람이 바로 봐야 하는 일만 보낸다: 매일 갱신 실패, 새 의견.
 * 구현은 실패해도 예외를 던지지 않아야 한다. 알림 때문에 본 작업이 실패하면 안 된다.
 */
public interface AlertSender {

    void send(String text);
}
