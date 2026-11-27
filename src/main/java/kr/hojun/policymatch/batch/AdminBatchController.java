package kr.hojun.policymatch.batch;

import java.util.Locale;
import java.util.Map;
import kr.hojun.policymatch.admin.AdminToken;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.ExternalApiException;
import kr.hojun.policymatch.collect.Source;
import kr.hojun.policymatch.collect.UnknownSourceException;
import kr.hojun.policymatch.normalize.MassDeactivationException;
import kr.hojun.policymatch.normalize.NormalizeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수집 · 정규화를 손으로 돌리는 관리용 엔드포인트.
 *
 * 공개 URL 에 붙어 있으므로 토큰(AdminToken) 없이는 실행되지 않게 한다.
 * 수집은 외부 API 를 수십 번 호출하고 DB 에 쓰기 때문에, 열어 두면 누구나 우리 인증키 쿼터를 소진시킬 수 있다.
 * 세 작업 모두 PolicyBatch 를 거치므로 매일 자동 실행(DailyRefreshJob)과 겹치면 409 로 거절된다 (D-020).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBatchController {

    private static final Logger log = LoggerFactory.getLogger(AdminBatchController.class);

    private final PolicyBatch batch;
    private final AdminToken adminToken;

    public AdminBatchController(PolicyBatch batch, AdminToken adminToken) {
        this.batch = batch;
        this.adminToken = adminToken;
    }

    /**
     * 출처 하나의 원본만 수집한다(정규화는 하지 않음).
     *
     * @param source youth(기본값, 이전과 같음) · kstartup · myhome(12/11). 토큰을 먼저 확인하므로, 토큰 없이는 어떤 출처가 있는지도 알려 주지 않는다.
     */
    @PostMapping("/collect")
    public CollectResult collect(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                 @RequestParam(defaultValue = "youth") String source) {
        adminToken.check(token);
        return batch.collect(Source.fromCode(source));
    }

    /**
     * @param source youth(기본값) · kstartup (12/10) · myhome (12/11)
     * @param force  true 면 대량 비활성 상한을 보지 않는다(D-019 "12/8 추가"). 상한에 걸려 멈춘 뒤, 원본 사이트에서
     *               정책이 실제로 사라진 것을 확인했을 때만 쓴다.
     */
    @PostMapping("/normalize")
    public NormalizeResult normalize(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                     @RequestParam(defaultValue = "youth") String source,
                                     @RequestParam(defaultValue = "false") boolean force) {
        adminToken.check(token);
        return batch.normalize(Source.fromCode(source), force);
    }

    /**
     * 수집 → 성공하면 정규화를 지금 한 번 돌린다. 기본값(youth)은 매일 자동 실행과 똑같은 일이다.
     *
     * @param source youth(기본값) · kstartup (12/10) · myhome (12/11)
     */
    @PostMapping("/refresh")
    public RefreshResult refresh(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                 @RequestParam(defaultValue = "youth") String source) {
        adminToken.check(token);
        return batch.refresh(Source.fromCode(source));
    }

    /**
     * API 쪽 실패는 우리 서버의 버그가 아니라 외부 의존 실패이므로 502 로 구분해 돌려준다.
     * 오류 이름에 출처를 붙인다: YOUTH_API_FAILURE(이전과 같음) · KSTARTUP_API_FAILURE.
     */
    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<Map<String, String>> handleApiFailure(ExternalApiException e) {
        log.error("수집 실패 — 출처 {}", e.source().code(), e);
        String error = e.source().code().toUpperCase(Locale.ROOT) + "_API_FAILURE";
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", error, "message", e.getMessage()));
    }

    /** ?source= 값이 없는 출처이거나 아직 수집기가 없는 출처. 요청이 잘못된 것이므로 400. */
    @ExceptionHandler(UnknownSourceException.class)
    public ResponseEntity<Map<String, String>> handleUnknownSource(UnknownSourceException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "UNKNOWN_SOURCE", "message", e.getMessage()));
    }

    /**
     * 대량 비활성 상한에 걸려 반영하지 않았다. 요청이 잘못된 것이 아니라 지금 데이터 상태로는 반영할 수 없다는 뜻이라 409.
     * 확인 뒤 force=true 로 다시 부르면 된다.
     */
    @ExceptionHandler(MassDeactivationException.class)
    public ResponseEntity<Map<String, String>> handleMassDeactivation(MassDeactivationException e) {
        log.warn("관리 요청 — 대량 비활성 감지로 반영하지 않음: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "MASS_DEACTIVATION", "message", e.getMessage()));
    }

    /** 다른 작업이 도는 중. 요청이 잘못된 것이 아니라 "지금은 안 됨"이므로 409. */
    @ExceptionHandler(BatchBusyException.class)
    public ResponseEntity<Map<String, String>> handleBusy(BatchBusyException e) {
        log.warn("관리 요청 거절 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "BATCH_RUNNING", "message", e.getMessage()));
    }
}
