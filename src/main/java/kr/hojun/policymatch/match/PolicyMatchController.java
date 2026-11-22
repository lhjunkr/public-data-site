package kr.hojun.policymatch.match;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyMatchController {

    private final PolicyMatchService service;
    private final PolicyDetailRateLimiter detailLimiter;

    public PolicyMatchController(PolicyMatchService service, PolicyDetailRateLimiter detailLimiter) {
        this.service = service;
        this.detailLimiter = detailLimiter;
    }

    @PostMapping("/match")
    public MatchResponse match(@RequestBody MatchRequest request) {
        return service.match(request);
    }

    /**
     * 정책 하나의 지원 내용 (D-027). 화면의 "자세히" 버튼이 부른다.
     * IP 는 HTTP 요청에만 있는 정보라 서비스가 아니라 여기서 거른다.
     * 프록시(Caddy) 뒤에서도 getRemoteAddr() 가 사용자 IP 다(forward-headers-strategy=native, D-025).
     */
    @GetMapping("/{id}")
    public PolicyDetail detail(@PathVariable long id, HttpServletRequest http) {
        if (!detailLimiter.tryAcquire(http.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "잠시 후 다시 시도해 주세요. 정책 상세는 1분에 "
                            + PolicyDetailRateLimiter.MAX_PER_WINDOW + "번까지 볼 수 있습니다.");
        }
        return service.detail(id);
    }
}
