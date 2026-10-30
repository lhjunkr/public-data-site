package kr.hojun.policymatch.batch;

import java.util.Map;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.YouthApiException;
import kr.hojun.policymatch.normalize.NormalizeResult;
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

/**
 * 수집 · 정규화를 손으로 돌리는 관리용 엔드포인트.
 *
 * 공개 URL 에 붙어 있으므로 토큰 없이는 실행되지 않게 한다.
 * 수집은 외부 API 를 수십 번 호출하고 DB 에 쓰기 때문에, 열어 두면 누구나 우리 인증키 쿼터를 소진시킬 수 있다.
 * 세 작업 모두 PolicyBatch 를 거치므로 매일 자동 실행(DailyRefreshJob)과 겹치면 409 로 거절된다 (D-020).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBatchController {

    private static final Logger log = LoggerFactory.getLogger(AdminBatchController.class);

    private final PolicyBatch batch;
    private final String adminToken;

    public AdminBatchController(PolicyBatch batch, @Value("${admin.token:}") String adminToken) {
        this.batch = batch;
        this.adminToken = adminToken;
    }

    @PostMapping("/collect")
    public CollectResult collect(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        checkToken(token);
        return batch.collect();
    }

    @PostMapping("/normalize")
    public NormalizeResult normalize(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        checkToken(token);
        return batch.normalize();
    }

    /** 매일 자동 실행과 똑같은 일(수집 → 성공하면 정규화)을 지금 한 번 돌린다. */
    @PostMapping("/refresh")
    public RefreshResult refresh(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        checkToken(token);
        return batch.refresh();
    }

    private void checkToken(String token) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "admin.token 이 설정되지 않아 실행할 수 없습니다.");
        }
        if (!adminToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token 이 올바르지 않습니다.");
        }
    }

    /** API 쪽 실패는 우리 서버의 버그가 아니라 외부 의존 실패이므로 502 로 구분해 돌려준다. */
    @ExceptionHandler(YouthApiException.class)
    public ResponseEntity<Map<String, String>> handleApiFailure(YouthApiException e) {
        log.error("수집 실패", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "YOUTH_API_FAILURE", "message", e.getMessage()));
    }

    /** 다른 작업이 도는 중. 요청이 잘못된 것이 아니라 "지금은 안 됨"이므로 409. */
    @ExceptionHandler(BatchBusyException.class)
    public ResponseEntity<Map<String, String>> handleBusy(BatchBusyException e) {
        log.warn("관리 요청 거절 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "BATCH_RUNNING", "message", e.getMessage()));
    }
}
