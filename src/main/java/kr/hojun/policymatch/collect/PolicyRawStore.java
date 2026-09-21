package kr.hojun.policymatch.collect;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * policy_raw 적재 담당.
 *
 * JPA 엔티티를 쓰지 않고 JdbcTemplate을 쓴 이유:
 *  - raw_json 은 MySQL JSON 타입이라 엔티티로 매핑하면 ddl-auto=validate 와 충돌할 여지가 있다.
 *  - 원본 보관 표는 조회·연관관계가 필요 없고 대량 삽입만 한다. ORM 이 줄 이점이 없다.
 *  - INSERT ... ON DUPLICATE KEY UPDATE 로 멱등성을 DB 한 줄에 맡길 수 있다.
 */
@Repository
public class PolicyRawStore {

    private static final String UPSERT_SQL = """
            INSERT INTO policy_raw (policy_no, raw_json, fetched_at)
            VALUES (?, ?, ?) AS src
            ON DUPLICATE KEY UPDATE
                raw_json   = src.raw_json,
                fetched_at = src.fetched_at
            """;

    private final JdbcTemplate jdbc;

    public PolicyRawStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 페이지 분량을 한 번에 적재한다. 같은 policy_no 는 덮어쓰므로 몇 번 돌려도 결과가 같다. */
    public int upsertAll(List<RawItem> items, LocalDateTime fetchedAt) {
        if (items.isEmpty()) {
            return 0;
        }
        Timestamp at = Timestamp.valueOf(fetchedAt);
        List<Object[]> batch = new ArrayList<>(items.size());
        for (RawItem item : items) {
            batch.add(new Object[] { item.policyNo(), item.rawJson(), at });
        }
        int[] affected = jdbc.batchUpdate(UPSERT_SQL, batch);
        return affected.length;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM policy_raw", Long.class);
        return n == null ? 0L : n;
    }

    /** 적재 단위. policy_no 와 원본 JSON 문자열 한 쌍. */
    public record RawItem(String policyNo, String rawJson) {}
}
