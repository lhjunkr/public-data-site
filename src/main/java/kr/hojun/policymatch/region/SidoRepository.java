package kr.hojun.policymatch.region;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SidoRepository {

    private final JdbcTemplate jdbcTemplate;

    public SidoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findAllSidoCodes() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT sido FROM region ORDER BY sido", String.class);
    }
}