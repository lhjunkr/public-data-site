package kr.hojun.policymatch.match;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyMatchController {

    private final PolicyMatchService service;

    public PolicyMatchController(PolicyMatchService service) {
        this.service = service;
    }

    @PostMapping("/match")
    public MatchResponse match(@RequestBody MatchRequest request) {
        return service.match(request);
    }

    /** 정책 하나의 지원 내용 (D-027). 화면의 "자세히" 버튼이 부른다 */
    @GetMapping("/{id}")
    public PolicyDetail detail(@PathVariable long id) {
        return service.detail(id);
    }
}
