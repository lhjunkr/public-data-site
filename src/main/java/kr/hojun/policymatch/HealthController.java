package kr.hojun.policymatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상태 확인 (D-026).
 *  - version: 이미지를 만들 때 넣은 커밋 7자리 (Dockerfile 의 APP_VERSION). 로컬 실행처럼 값이 없으면 "local".
 *  - db: DB 에 SELECT 1 이 되는지. 안 되면 503 을 돌려 외부 감시(UptimeRobot)와 deploy.sh 가 장애로 본다.
 *    예전에는 DB 와 상관없이 항상 UP 이라, DB 가 죽어도 감시가 알아채지 못했다.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    public record HealthResponse(String status, String db, String version) {}

    private final JdbcTemplate jdbc;
    private final String version;

    public HealthController(JdbcTemplate jdbc, @Value("${APP_VERSION:local}") String version) {
        this.jdbc = jdbc;
        this.version = version;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        if (dbUp()) {
            return ResponseEntity.ok(new HealthResponse("UP", "UP", version));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("DOWN", "DOWN", version));
    }

    /** DB 에 가장 가벼운 질의를 한 번 보낸다. 어떤 이유로든 실패하면 DOWN */
    private boolean dbUp() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            log.warn("상태 확인: DB 연결 실패 — {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
