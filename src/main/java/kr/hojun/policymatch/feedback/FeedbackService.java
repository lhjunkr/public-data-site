package kr.hojun.policymatch.feedback;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * "의견 보내기" 입력 검증 → 도배 방지 → 저장 (D-024).
 *
 * 이메일은 선택이고, 적었다면 개인정보 수집 · 이용 동의(consent)가 있어야 받는다(개인정보 보호법 제15조).
 * 동의 화면에 알리는 네 가지(항목 · 목적 · 보관 기간 · 거부 권리)는 index.html 의 안내문에 있다.
 */
@Service
public class FeedbackService {

    static final int MAX_MESSAGE = 1000;
    static final int MAX_EMAIL = 254;
    /** 하루에 받는 의견 상한. 여러 IP 로 도배해도 DB 가 끝없이 커지지 않게 한다 */
    static final int DAILY_LIMIT = 200;

    /** 형식만 대략 본다(무엇@무엇.무엇). 실제로 받을 수 있는 주소인지는 답장을 보내 봐야 안다 */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final FeedbackStore store;
    private final FeedbackRateLimiter limiter;

    public FeedbackService(FeedbackStore store, FeedbackRateLimiter limiter) {
        this.store = store;
        this.limiter = limiter;
    }

    /**
     * @param clientIp 도배 방지용. 저장하지 않는다
     * @return 저장했으면 true. 자동 입력(봇)으로 보여 저장하지 않았으면 false (응답은 똑같이 성공)
     */
    public boolean submit(FeedbackRequest request, String clientIp) {
        if (request == null) {
            throw badRequest("요청 본문이 비어 있습니다.");
        }
        // 화면에 보이지 않는 칸(website)에 값이 있으면 사람이 아니라 자동 입력 프로그램이다.
        // 오류로 알려 주면 피하는 방법을 가르쳐 주는 셈이라, 성공처럼 응답하고 저장만 하지 않는다.
        if (request.website() != null && !request.website().isBlank()) {
            return false;
        }

        String message = request.message() == null ? "" : request.message().strip();
        if (message.isEmpty()) {
            throw badRequest("의견을 입력해 주세요.");
        }
        if (message.length() > MAX_MESSAGE) {
            throw badRequest("의견은 " + MAX_MESSAGE + "자까지 쓸 수 있습니다.");
        }

        String email = request.email() == null || request.email().isBlank() ? null : request.email().strip();
        if (email != null) {
            if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
                throw badRequest("이메일 주소 형식이 올바르지 않습니다.");
            }
            if (!Boolean.TRUE.equals(request.consent())) {
                throw badRequest("답장을 받으려면 개인정보 수집 · 이용에 동의해 주세요. 동의하지 않으려면 이메일 칸을 비워 주세요.");
            }
        }

        if (!limiter.tryAcquire(clientIp)) {
            throw tooMany("잠시 후 다시 보내 주세요. 10분에 " + FeedbackRateLimiter.MAX_PER_WINDOW + "번까지 보낼 수 있습니다.");
        }
        if (store.countToday() >= DAILY_LIMIT) {
            throw tooMany("오늘은 의견을 더 받을 수 없습니다. 내일 다시 보내 주세요.");
        }

        store.insert(message, email);
        return true;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException tooMany(String message) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
