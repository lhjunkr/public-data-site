package kr.hojun.policymatch.match;

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
}
