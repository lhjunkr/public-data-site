package kr.hojun.policymatch.collect;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 화면 하단의 기준 시각 API 와, 삭제한 전체 목록 API (D-023).
 * 시각은 2099년으로 넣어 로컬 DB 에 실제 수집 기록이 있어도 이 행이 가장 최근이 되게 한다.
 */
@SpringBootTest
@Transactional
class DataStatusTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 기준_시각은_마지막_성공_수집의_시작_시각이다() throws Exception {
        jdbc.update("INSERT INTO collect_run (started_at, status) VALUES ('2099-01-02 04:10:00', 'SUCCESS')");
        // 11/1 처럼 다음 날 실패하면 화면에는 전날 시각이 그대로 보여야 한다
        jdbc.update("INSERT INTO collect_run (started_at, status) VALUES ('2099-01-03 04:10:00', 'FAILED')");

        mvc.perform(get("/api/v1/data-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value("2099-01-02 04:10"));
    }

    @Test
    void 전체_목록_API는_없다() throws Exception {
        // 정규화된 정책 전체를 통째로 내주던 GET /api/v1/policies 는 D-023 에서 삭제했다
        mvc.perform(get("/api/v1/policies"))
                .andExpect(status().isNotFound());
    }
}
