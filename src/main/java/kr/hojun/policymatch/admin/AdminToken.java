package kr.hojun.policymatch.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리용 엔드포인트의 X-Admin-Token 확인 (수집 · 정규화 · 의견 관리가 함께 쓴다).
 *
 * 공개 URL 에 붙어 있으므로 토큰 없이는 실행되지 않게 한다. 서버에 토큰이 없으면 503, 틀리면 401.
 * 비교는 MessageDigest.isEqual 로 한다. 문자열 equals 는 앞 글자부터 다르면 바로 끝나서,
 * 응답 시간 차이로 토큰을 한 글자씩 맞혀 보는 공격(타이밍 공격)의 단서가 될 수 있다.
 */
@Component
public class AdminToken {

    private final byte[] token;

    public AdminToken(@Value("${admin.token:}") String token) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    public void check(String given) {
        if (token.length == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "admin.token 이 설정되지 않아 실행할 수 없습니다.");
        }
        byte[] g = given == null ? new byte[0] : given.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(token, g)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token 이 올바르지 않습니다.");
        }
    }
}
