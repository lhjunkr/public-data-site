package kr.hojun.policymatch.match;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 입력 검증 → 매칭 실행.
 * 검증을 SQL 앞에서 하는 이유: 잘못된 값이 SQL 까지 가면 오류 없이 틀린 답이 나올 수 있다
 * (9/25 실측: 문자열 "NULL" 이 0 으로 바뀌어 '충족'이 나옴).
 */
@Service
public class PolicyMatchService {

    static final int MIN_AGE = 0;
    static final int MAX_AGE = 120;

    private final PolicyMatchRepository repository;

    public PolicyMatchService(PolicyMatchRepository repository) {
        this.repository = repository;
    }

    public MatchResponse match(MatchRequest request) {
        validate(request);
        List<MatchedPolicy> policies = repository.match(request.age(), request.sido(), request.income());
        return new MatchResponse(policies.size(), policies);
    }

    void validate(MatchRequest request) {
        if (request == null) {
            throw badRequest("요청 본문이 비어 있습니다.");
        }
        if (request.age() == null) {
            throw badRequest("age 는 필수입니다.");
        }
        if (request.age() < MIN_AGE || request.age() > MAX_AGE) {
            throw badRequest("age 는 " + MIN_AGE + "~" + MAX_AGE + " 사이여야 합니다.");
        }
        if (request.sido() == null || !request.sido().matches("\\d{2}")) {
            throw badRequest("sido 는 두 자리 숫자 코드여야 합니다. 예: \"11\"");
        }
        if (request.income() != null && request.income() < 0) {
            throw badRequest("income 은 0 이상이어야 합니다. (만원 단위)");
        }
        if (!repository.sidoExists(request.sido())) {
            throw badRequest("존재하지 않는 시도 코드입니다: " + request.sido());
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
