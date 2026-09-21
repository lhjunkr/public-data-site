package kr.hojun.policymatch.normalize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 정규화 적재를 수동으로 돌리는 관리용 엔드포인트. 수집과 같은 토큰으로 보호한다. */
@RestController
@RequestMapping("/api/v1/admin")
public class NormalizeController {

    private static final Logger log = LoggerFactory.getLogger(NormalizeController.class);

    private final PolicyNormalizer normalizer;
    private final String adminToken;

    public NormalizeController(PolicyNormalizer normalizer, @Value("${admin.token:}") String adminToken) {
        this.normalizer = normalizer;
        this.adminToken = adminToken;
    }

    @PostMapping("/normalize")
    public NormalizeResult normalize(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "admin.token 이 설정되지 않아 실행할 수 없습니다.");
        }
        if (!adminToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token 이 올바르지 않습니다.");
        }
        log.info("정규화 적재 요청");
        return normalizer.normalize();
    }
}
