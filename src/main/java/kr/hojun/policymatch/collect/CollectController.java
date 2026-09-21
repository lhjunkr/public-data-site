package kr.hojun.policymatch.collect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 수집 배치를 수동으로 돌리는 관리용 엔드포인트.
 *
 * 공개 URL 에 붙어 있으므로 토큰 없이는 실행되지 않게 한다.
 * 이 엔드포인트는 외부 API 를 수십 번 호출하고 DB 에 쓰기 때문에,
 * 열어두면 누구나 우리 인증키 쿼터를 소진시킬 수 있다.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class CollectController {

    private static final Logger log = LoggerFactory.getLogger(CollectController.class);

    private final PolicyCollector collector;
    private final String adminToken;

    public CollectController(PolicyCollector collector, @Value("${admin.token:}") String adminToken) {
        this.collector = collector;
        this.adminToken = adminToken;
    }

    @PostMapping("/collect")
    public CollectResult collect(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "admin.token 이 설정되지 않아 수집을 실행할 수 없습니다.");
        }
        if (!adminToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token 이 올바르지 않습니다.");
        }
        return collector.collectAll();
    }

    /** API 쪽 실패는 우리 서버의 버그가 아니라 외부 의존 실패이므로 502 로 구분해 돌려준다. */
    @ExceptionHandler(YouthApiException.class)
    public ResponseEntity<Map<String, String>> handleApiFailure(YouthApiException e) {
        log.error("수집 실패", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "YOUTH_API_FAILURE", "message", e.getMessage()));
    }
}
