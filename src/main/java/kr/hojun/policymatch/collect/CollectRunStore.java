package kr.hojun.policymatch.collect;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 수집 실행 기록(collect_run) 담당 (D-017).
 *
 * 시작할 때 RUNNING 으로 한 줄을 만들고, 끝나면 SUCCESS 또는 FAILED 로 바꾼다.
 * 앱이 수집 도중 꺼지면 RUNNING 으로 남는데, 성공이 아니므로 정규화 기준에 쓰이지 않는다.
 */
@Repository
public class CollectRunStore {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private static final int MESSAGE_MAX = 500;

    private final JdbcTemplate jdbc;

    public CollectRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 실행 기록을 만들고 id 를 돌려준다.
     * id 를 LAST_INSERT_ID() 로 따로 조회하지 않는 이유: 커넥션 풀에서 다른 커넥션을 받으면 엉뚱한 값이 나온다.
     */
    public long start(LocalDateTime startedAt) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO collect_run (started_at, status) VALUES (?, 'RUNNING')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.valueOf(startedAt));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /**
     * 마지막으로 성공한 수집이 시작된 시각을 "yyyy-MM-dd HH:mm" 글자로 돌려준다. 성공한 수집이 없으면 비어 있다.
     *
     * 글자로 바꾸는 일을 DB(DATE_FORMAT)에서 하는 이유: DATETIME 을 자바 시각 객체(Timestamp)로 읽으면
     * JDBC 드라이버가 연결 시간대(serverTimezone=Asia/Seoul)와 JVM 시간대의 차이만큼 시각을 옮긴다.
     * 서버는 JVM 도 한국 시간이라 차이가 없지만, CI 는 JVM 이 UTC 라 9시간이 밀린다(11/2 CI 실패).
     * 저장된 값(한국 시간)을 그대로 보여 주려면 DB 에서 글자로 꺼내는 편이 확실하다.
     */
    public Optional<String> lastSuccessStartedAt() {
        String text = jdbc.queryForObject(
                "SELECT DATE_FORMAT(MAX(started_at), '%Y-%m-%d %H:%i') FROM collect_run WHERE status = 'SUCCESS'",
                String.class);
        return Optional.ofNullable(text);
    }

    public void finish(long id, String status, int totCount, int itemsSeen, String message) {
        String msg = message == null || message.length() <= MESSAGE_MAX ? message : message.substring(0, MESSAGE_MAX);
        jdbc.update("""
                UPDATE collect_run
                SET finished_at = NOW(), status = ?, tot_count = ?, items_seen = ?, message = ?
                WHERE id = ?
                """, status, totCount, itemsSeen, msg, id);
    }
}
