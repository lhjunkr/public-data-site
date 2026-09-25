package kr.hojun.policymatch.match;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 통합 테스트: HTTP 요청 → 컨트롤러 → 검증 → SQL → JSON 응답까지 한 번에 관통한다.
 * 단위 테스트가 부품을 따로 시험한다면, 이 테스트는 부품이 제대로 연결됐는지를 본다.
 */
@SpringBootTest
@Transactional
class PolicyMatchApiIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        jdbc.update("INSERT INTO region (code, sido, name) VALUES ('99001','99','시험구1')");
        jdbc.update("""
                INSERT INTO policy (policy_no, title, age_limit_yn, min_age, max_age,
                                    earn_cnd_se_cd, earn_max_amt, view_count)
                VALUES ('T-API', 'T-API', 'N', 19, 39, '0043002', 5000, 0)
                """);
        jdbc.update("""
                INSERT INTO policy_region (policy_id, region_code)
                SELECT id, '99001' FROM policy WHERE policy_no = 'T-API'
                """);
    }

    @Test
    void 정상_요청은_200과_3state_라벨을_돌려준다() throws Exception {
        mvc.perform(post("/api/v1/policies/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age\":25,\"sido\":\"99\",\"income\":6000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.policies[0].title").value("T-API"))
                .andExpect(jsonPath("$.policies[0].incomeStatus").value("NOT_MET"));
    }

    @Test
    void 잘못된_요청은_400과_사유를_돌려준다() throws Exception {
        mvc.perform(post("/api/v1/policies/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sido\":\"99\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("age 는 필수입니다."));
    }
}
