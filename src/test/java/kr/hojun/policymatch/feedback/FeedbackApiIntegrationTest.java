package kr.hojun.policymatch.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 의견 보내기 → 관리 목록 → 처리 완료(이메일 삭제)까지 HTTP 로 관통한다 (D-024).
 * 테스트마다 다른 IP 로 보내 도배 방지 한도(10분 3번)가 다른 테스트에 걸리지 않게 한다.
 */
@SpringBootTest(properties = "admin.token=test-token")
@Transactional
class FeedbackApiIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void 의견을_보내면_201이고_이메일과_함께_저장된다() throws Exception {
        mvc.perform(post("/api/v1/feedback").with(from("10.9.0.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"T-FEEDBACK 답장 부탁\",\"email\":\"me@example.com\",\"consent\":true}"))
                .andExpect(status().isCreated());

        assertEquals("me@example.com", jdbc.queryForObject(
                "SELECT email FROM feedback WHERE message = 'T-FEEDBACK 답장 부탁'", String.class));
    }

    @Test
    void 동의_없이_이메일을_보내면_400과_사유() throws Exception {
        mvc.perform(post("/api/v1/feedback").with(from("10.9.0.2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"T-FEEDBACK\",\"email\":\"me@example.com\",\"consent\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void 관리_목록은_토큰이_있어야_보이고_처리하면_이메일이_지워진다() throws Exception {
        jdbc.update("INSERT INTO feedback (message, email) VALUES ('T-FEEDBACK 관리', 'me@example.com')");
        Long id = jdbc.queryForObject("SELECT id FROM feedback WHERE message = 'T-FEEDBACK 관리'", Long.class);

        mvc.perform(get("/api/v1/admin/feedback"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/feedback").header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.intValue()))
                .andExpect(jsonPath("$[0].email").value("me@example.com"));

        mvc.perform(post("/api/v1/admin/feedback/" + id + "/done").header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk());

        assertNull(jdbc.queryForObject("SELECT email FROM feedback WHERE id = ?", String.class, id));
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM feedback WHERE id = ?", String.class, id));
    }

    @Test
    void 보관_기간이_지난_이메일만_지운다() {
        jdbc.update("INSERT INTO feedback (created_at, message, email) VALUES "
                + "(NOW() - INTERVAL 100 DAY, 'T-OLD', 'old@example.com'), "
                + "(NOW() - INTERVAL 10 DAY, 'T-RECENT', 'recent@example.com')");

        new FeedbackRetentionJob(new FeedbackStore(jdbc)).purge();

        assertNull(jdbc.queryForObject("SELECT email FROM feedback WHERE message = 'T-OLD'", String.class));
        assertEquals("recent@example.com",
                jdbc.queryForObject("SELECT email FROM feedback WHERE message = 'T-RECENT'", String.class));
    }
}
