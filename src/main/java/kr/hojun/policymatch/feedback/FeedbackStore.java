package kr.hojun.policymatch.feedback;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** feedback 표 담당 (D-024). 접속 IP 는 저장하지 않는다. */
@Repository
public class FeedbackStore {

    /** 답장하지 못한 의견의 이메일도 이 기간이 지나면 지운다. 화면의 개인정보 안내와 같은 값이어야 한다. */
    public static final int EMAIL_RETENTION_DAYS = 90;

    public static final String NEW = "NEW";
    public static final String DONE = "DONE";

    private final JdbcTemplate jdbc;

    public FeedbackStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String message, String email) {
        jdbc.update("INSERT INTO feedback (message, email) VALUES (?, ?)", message, email);
    }

    /** 오늘(DB 시간 = 한국 시간) 받은 의견 수. 하루 상한 확인용 */
    public int countToday() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM feedback WHERE created_at >= CURDATE()", Integer.class);
        return n == null ? 0 : n;
    }

    /** 상태별 최근 100건, 새것부터 */
    public List<FeedbackItem> list(String status) {
        return jdbc.query("""
                SELECT id, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS created, message, email, status
                FROM feedback
                WHERE status = ?
                ORDER BY id DESC
                LIMIT 100
                """,
                (rs, rowNum) -> new FeedbackItem(rs.getLong("id"), rs.getString("created"),
                        rs.getString("message"), rs.getString("email"), rs.getString("status")),
                status);
    }

    /**
     * 답장 · 처리를 마쳤다. 이메일은 이 순간 지운다 (보관 목적이 끝났으므로).
     *
     * @return 바뀐 행 수. 없는 id 면 0
     */
    public int markDone(long id) {
        return jdbc.update("UPDATE feedback SET status = 'DONE', answered_at = NOW(), email = NULL WHERE id = ?", id);
    }

    /** 보관 기간이 지난 이메일을 지운다. 의견 본문은 남긴다. @return 지운 건수 */
    public int purgeExpiredEmails() {
        return jdbc.update("UPDATE feedback SET email = NULL WHERE email IS NOT NULL AND created_at < NOW() - INTERVAL "
                + EMAIL_RETENTION_DAYS + " DAY");
    }
}
