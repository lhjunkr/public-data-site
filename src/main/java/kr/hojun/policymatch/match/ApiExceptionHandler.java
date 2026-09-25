package kr.hojun.policymatch.match;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 매칭 API 의 오류를 한 가지 모양 {status, error, message} 로 통일한다.
 * 스프링 기본 오류 응답은 설정에 따라 message 를 숨기므로, 400 사유가 확실히 전달되도록 직접 만든다.
 */
@RestControllerAdvice(assignableTypes = PolicyMatchController.class)
public class ApiExceptionHandler {

    /** 서비스의 입력 검증 실패 (age 누락, 없는 시도 코드 등) */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatus(ResponseStatusException e) {
        return body(e.getStatusCode(), e.getReason());
    }

    /** JSON 자체를 해석할 수 없음 (예: income 에 문자열 "NULL") */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST,
                "요청 JSON 형식이 올바르지 않습니다. age·income 은 숫자, sido 는 문자열이어야 합니다.");
    }

    private static ResponseEntity<ApiErrorResponse> body(HttpStatusCode code, String message) {
        HttpStatus status = HttpStatus.valueOf(code.value());
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(status.value(), status.getReasonPhrase(), message));
    }
}
