package kr.hojun.policymatch.match;

import java.util.List;

public record MatchResponse(int count, List<MatchedPolicy> policies) {}
