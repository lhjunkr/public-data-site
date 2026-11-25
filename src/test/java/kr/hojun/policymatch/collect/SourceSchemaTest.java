package kr.hojun.policymatch.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V10 (D-029) 이 만든 출처(source) 구조를 DB 수준에서 확인한다.
 * 코드가 아니라 표의 약속(기본값 · 유니크 키)이 지켜지는지 보는 테스트다. 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class SourceSchemaTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void 출처를_적지_않은_이전_코드의_INSERT는_youth로_들어간다() {
        // 배포 뒤 이전 이미지로 되돌려도 동작해야 한다(확장 단계라 기본값을 남김).
        // 이전 코드는 collect_run · policy_raw · policy 에 source 없이 넣는다
        jdbc.update("INSERT INTO collect_run (started_at, status) VALUES ('2099-01-01 00:00:00', 'RUNNING')");
        jdbc.update("INSERT INTO policy_raw (policy_no, raw_json, fetched_at) VALUES ('T-S-OLD', '{}', '2099-01-01 00:00:00')");
        jdbc.update("INSERT INTO policy (policy_no, title) VALUES ('T-S-OLD', '이전 코드')");

        assertEquals("youth", jdbc.queryForObject(
                "SELECT source FROM collect_run WHERE started_at = '2099-01-01 00:00:00'", String.class));
        assertEquals("youth", jdbc.queryForObject(
                "SELECT source FROM policy_raw WHERE policy_no = 'T-S-OLD'", String.class));
        assertEquals("youth", jdbc.queryForObject(
                "SELECT source FROM policy WHERE policy_no = 'T-S-OLD'", String.class));
    }

    @Test
    void 같은_번호라도_출처가_다르면_함께_있을_수_있다() {
        jdbc.update("INSERT INTO policy (source, policy_no, title) VALUES ('youth', 'T-S-1', '온통청년')");
        jdbc.update("INSERT INTO policy (source, policy_no, title) VALUES ('kstartup', 'T-S-1', 'K-Startup')");

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM policy WHERE policy_no = 'T-S-1'", Integer.class));
    }

    @Test
    void 같은_출처에서_번호가_겹치면_DB가_막는다() {
        jdbc.update("INSERT INTO policy (source, policy_no, title) VALUES ('youth', 'T-S-2', '처음')");

        assertThrows(DuplicateKeyException.class, () ->
                jdbc.update("INSERT INTO policy (source, policy_no, title) VALUES ('youth', 'T-S-2', '중복')"));
    }
}
