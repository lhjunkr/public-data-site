package kr.hojun.policymatch.feedback;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import kr.hojun.policymatch.match.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 화면의 "의견 보내기" 창이 부르는 API (D-024). 오류 모양은 매칭 API 와 같은 {status, error, message} */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody FeedbackRequest request, HttpServletRequest http) {
        boolean saved = service.submit(request, http.getRemoteAddr());
        if (saved) {
            log.info("의견 접수");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "보내 주셔서 감사합니다."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatus(ResponseStatusException e) {
        return body(e.getStatusCode(), e.getReason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");
    }

    private static ResponseEntity<ApiErrorResponse> body(HttpStatusCode code, String message) {
        HttpStatus status = HttpStatus.valueOf(code.value());
        return ResponseEntity.status(status).body(new ApiErrorResponse(status.value(), status.getReasonPhrase(), message));
    }
}
