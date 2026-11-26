package kr.hojun.policymatch.batch;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 대량 비활성으로 멈춘 뒤 사람이 강제 반영하는 운영 경로를 HTTP 로 관통한다 (D-019 "12/8 추가").
 * 관리 API → PolicyBatch → 실제 정규화 → 실제 MySQL. 끝나면 롤백된다.
 */
@SpringBootTest(properties = "admin.token=test-token")
@Transactional
class AdminBatchApiIntegrationTest {

    private static final String AT = "2099-01-02 00:00:00";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        // 로컬 DB 의 실제 정책은 비활성으로 두고 시험용만 남긴다 (PolicyNormalizerTest 와 같은 이유)
        jdbc.update("UPDATE policy SET active = FALSE WHERE source = 'youth' AND active = TRUE AND policy_no NOT LIKE 'T-%'");
        // 원본에는 1건만 있고, 지금 활성인 정책 51건은 원본에 없다 → 51건 비활성 예정, 상한 50건
        jdbc.update("""
                INSERT INTO policy_raw (source, policy_no, raw_json, fetched_at) VALUES ('youth', 'T-A-1', ?, ?)
                """, """
                {"plcyNo":"T-A-1","plcyNm":"T-A-1","plcyAprvSttsCd":"0044002","mclsfNm":"주택 및 거주지",
                 "sprtTrgtAgeLmtYn":"Y","sprtTrgtMinAge":"0","sprtTrgtMaxAge":"0",
                 "earnCndSeCd":"0043001","zipCd":"99001","jobCd":"0013010"}
                """, AT);
        jdbc.update("INSERT INTO collect_run (source, started_at, finished_at, status) VALUES ('youth', ?, ?, 'SUCCESS')",
                AT, AT);
        for (int i = 0; i < 51; i++) {
            jdbc.update("INSERT INTO policy (source, policy_no, title) VALUES ('youth', ?, '원본에 없는 정책')", "T-A-OLD-" + i);
        }
    }

    @Test
    void 상한을_넘으면_409와_건수를_돌려준다() throws Exception {
        mvc.perform(post("/api/v1/admin/normalize").header("X-Admin-Token", "test-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MASS_DEACTIVATION"))
                .andExpect(jsonPath("$.message").value(containsString("51")));
    }

    @Test
    void 확인_뒤_force로_부르면_반영한다() throws Exception {
        mvc.perform(post("/api/v1/admin/normalize").header("X-Admin-Token", "test-token"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/admin/normalize").param("force", "true").header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies").value(1))
                .andExpect(jsonPath("$.deactivated").value(51));
    }

    @Test
    void 토큰_없이는_force도_실행되지_않는다() throws Exception {
        mvc.perform(post("/api/v1/admin/normalize").param("force", "true"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 모르는_출처를_수집하라고_하면_400과_가능한_값을_돌려준다() throws Exception {
        // 12/9: /collect?source= 로 출처를 고른다. 외부 API 는 부르지 않는다
        mvc.perform(post("/api/v1/admin/collect").param("source", "nope").header("X-Admin-Token", "test-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_SOURCE"))
                .andExpect(jsonPath("$.message").value(containsString("kstartup")));
    }

    @Test
    void 모르는_출처를_정규화하라고_하면_400() throws Exception {
        mvc.perform(post("/api/v1/admin/normalize").param("source", "nope").header("X-Admin-Token", "test-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_SOURCE"));
    }

    @Test
    void 토큰_없이는_출처_수집도_실행되지_않는다() throws Exception {
        // 토큰을 먼저 확인한다. 토큰 없이는 외부 API 를 부르지 않고, 어떤 출처가 있는지도 알려 주지 않는다
        mvc.perform(post("/api/v1/admin/collect").param("source", "kstartup"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/collect").param("source", "nope"))
                .andExpect(status().isUnauthorized());
    }
}
