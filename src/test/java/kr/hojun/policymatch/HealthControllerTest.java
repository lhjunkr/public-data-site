package kr.hojun.policymatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 상태 확인 응답에 생성자로 받은 버전이 그대로 실리는지 본다. 스프링을 띄우지 않는다. */
class HealthControllerTest {

    @Test
    void 응답에_상태와_버전이_담긴다() {
        HealthController.HealthResponse response = new HealthController("abc1234").health();

        assertEquals("UP", response.status());
        assertEquals("abc1234", response.version());
    }
}
