package kr.hojun.policymatch.feedback;

import java.util.List;
import java.util.Map;
import kr.hojun.policymatch.admin.AdminToken;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 받은 의견을 읽고, 답장을 마친 의견을 처리 완료로 바꾸는 관리용 엔드포인트 (D-024).
 * 수집 · 정규화와 같은 토큰(X-Admin-Token)으로 보호한다. 이메일이 들어 있으므로 토큰 없이는 절대 열지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
public class AdminFeedbackController {

    private final FeedbackStore store;
    private final AdminToken adminToken;

    public AdminFeedbackController(FeedbackStore store, AdminToken adminToken) {
        this.store = store;
        this.adminToken = adminToken;
    }

    /** 상태별 최근 100건. 기본은 아직 처리하지 않은 의견(NEW) */
    @GetMapping
    public List<FeedbackItem> list(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                   @RequestParam(defaultValue = FeedbackStore.NEW) String status) {
        adminToken.check(token);
        if (!FeedbackStore.NEW.equals(status) && !FeedbackStore.DONE.equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 는 NEW 또는 DONE 입니다.");
        }
        return store.list(status);
    }

    /** 답장 · 처리를 마쳤다. 이메일은 이때 지운다 */
    @PostMapping("/{id}/done")
    public Map<String, Object> done(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @PathVariable long id) {
        adminToken.check(token);
        if (store.markDone(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 의견입니다: " + id);
        }
        return Map.of("id", id, "status", FeedbackStore.DONE);
    }
}
