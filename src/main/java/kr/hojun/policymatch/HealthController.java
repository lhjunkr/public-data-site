package kr.hojun.policymatch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상태 확인. version 은 이미지를 만들 때 넣은 커밋 7자리다 (Dockerfile 의 APP_VERSION).
 * 배포 뒤 이 값을 보면 서버가 지금 어느 커밋을 돌리고 있는지 바로 알 수 있다.
 * 로컬 실행처럼 값이 없으면 "local".
 */
@RestController
public class HealthController {

    public record HealthResponse(String status, String version) {}

    private final String version;

    public HealthController(@Value("${APP_VERSION:local}") String version) {
        this.version = version;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", version);
    }
}
