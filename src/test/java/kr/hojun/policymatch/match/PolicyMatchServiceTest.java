package kr.hojun.policymatch.match;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 입력 검증 단위 테스트. DB 도 스프링도 띄우지 않는다.
 * 저장소를 가짜로 바꿔 끼워 "검증 로직만" 떼어 시험한다.
 */
class PolicyMatchServiceTest {

    /** 진짜 DB 대신 쓰는 가짜 저장소. 시도 코드는 "11" 하나만 존재한다고 가정한다. */
    static class FakeRepository extends PolicyMatchRepository {
        FakeRepository() { super(null); }

        @Override
        public boolean sidoExists(String sido) { return "11".equals(sido); }

        @Override
        public List<MatchedPolicy> match(int age, String sido, Long income) { return List.of(); }
    }

    private final PolicyMatchService service = new PolicyMatchService(new FakeRepository());

    private void assertBadRequest(MatchRequest request) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.match(request));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    private void assertAccepted(MatchRequest request) {
        assertDoesNotThrow(() -> service.match(request));
    }

    @Test
    void 요청_본문이_없으면_400() {
        assertBadRequest(null);
    }

    @Test
    void 나이가_없으면_400() {
        assertBadRequest(new MatchRequest(null, "11", null));
    }

    @Test
    void 나이_경계_0과_120은_통과_범위_밖은_400() {
        assertBadRequest(new MatchRequest(-1, "11", null));
        assertAccepted(new MatchRequest(0, "11", null));
        assertAccepted(new MatchRequest(120, "11", null));
        assertBadRequest(new MatchRequest(121, "11", null));
    }

    @Test
    void 시도는_두자리_숫자만_통과() {
        assertBadRequest(new MatchRequest(25, null, null));
        assertBadRequest(new MatchRequest(25, "1", null));
        assertBadRequest(new MatchRequest(25, "111", null));
        assertBadRequest(new MatchRequest(25, "ab", null));
        assertAccepted(new MatchRequest(25, "11", null));
    }

    @Test
    void 형식은_맞아도_없는_시도코드는_400() {
        // 9/25 실측: 옛 전남 코드 46 은 조용히 0건이 나왔다 → 오류로 알려야 한다
        assertBadRequest(new MatchRequest(25, "46", null));
    }

    @Test
    void 소득_경계_0은_통과_음수는_400_생략은_통과() {
        assertBadRequest(new MatchRequest(25, "11", -1L));
        assertAccepted(new MatchRequest(25, "11", 0L));
        assertAccepted(new MatchRequest(25, "11", null));
    }
}
