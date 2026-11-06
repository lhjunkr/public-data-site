package kr.hojun.policymatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import kr.hojun.policymatch.HealthController.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 상태 확인 (D-026). 스프링과 진짜 DB 없이, 정해진 대로 성공하거나 실패하는 가짜 JdbcTemplate 으로 규칙만 본다. */
class HealthControllerTest {

    /** SELECT 1 에 1 을 돌려주거나(DB 정상), 연결 실패 예외를 던진다(DB 장애) */
    private static JdbcTemplate fakeDb(boolean up) {
        return new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                if (!up) {
                    throw new CannotGetJdbcConnectionException("DB 에 연결할 수 없음");
                }
                return requiredType.cast(1);
            }
        };
    }

    @Test
    void DB가_되면_200과_UP_그리고_버전() {
        ResponseEntity<HealthResponse> response = new HealthController(fakeDb(true), "abc1234").health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().status());
        assertEquals("UP", response.getBody().db());
        assertEquals("abc1234", response.getBody().version());
    }

    @Test
    void DB가_안되면_503과_DOWN() {
        ResponseEntity<HealthResponse> response = new HealthController(fakeDb(false), "abc1234").health();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().status());
        assertEquals("DOWN", response.getBody().db());
        assertEquals("abc1234", response.getBody().version(), "장애 중에도 어느 버전인지는 알려 준다");
    }
}
