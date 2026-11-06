package kr.hojun.policymatch.alert;

import java.util.ArrayList;
import java.util.List;

/** 테스트용 가짜 알림. 디스코드로 보내지 않고, 보내려던 글만 기억한다 (D-026). */
public class FakeAlertSender implements AlertSender {

    public final List<String> sent = new ArrayList<>();

    @Override
    public void send(String text) {
        sent.add(text);
    }
}
