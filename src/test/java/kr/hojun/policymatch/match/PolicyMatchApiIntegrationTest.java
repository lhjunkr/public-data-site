package kr.hojun.policymatch.match;

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
        jdbc.update("""
                INSERT INTO policy_sido (policy_id, sido)
                SELECT id, '99' FROM policy WHERE policy_no = 'T-API'
                """);
        jdbc.update("""
                INSERT INTO policy_category (policy_id, category_code)
                SELECT id, 'housing.rent' FROM policy WHERE policy_no = 'T-API'
                """);
        jdbc.update("""
                INSERT INTO policy_job (policy_id, job_code)
                SELECT id, '0013001' FROM policy WHERE policy_no = 'T-API'
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
                .andExpect(jsonPath("$.policies[0].incomeStatus").value("NOT_MET"))
                .andExpect(jsonPath("$.policies[0].categories[0]").value("housing.rent"))
                .andExpect(jsonPath("$.policies[0].supportContent").doesNotExist());
    }

    @Test
    void 취업_상태가_맞지_않으면_결과에서_빠진다() throws Exception {
        // T-API 는 재직자(0013001) 전용
        mvc.perform(post("/api/v1/policies/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age\":25,\"sido\":\"99\",\"job\":\"0013003\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        mvc.perform(post("/api/v1/policies/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age\":25,\"sido\":\"99\",\"job\":\"0013001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void 취업_상태_목록은_제한없음을_빼고_화면_순서대로_준다() throws Exception {
        mvc.perform(get("/api/v1/job-statuses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$[0].code").value("0013003"))
                .andExpect(jsonPath("$[0].name").value("미취업자"));
    }

    @Test
    void 잘못된_요청은_400과_사유를_돌려준다() throws Exception {
        mvc.perform(post("/api/v1/policies/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sido\":\"99\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("age 는 필수입니다."));
    }


    @Test
    void 상세_조회는_200과_지원_내용을_돌려준다() throws Exception {
        jdbc.update("UPDATE policy SET support_content = '월 20만 원 지원' WHERE policy_no = 'T-API'");
        Long id = jdbc.queryForObject("SELECT id FROM policy WHERE policy_no = 'T-API'", Long.class);

        mvc.perform(get("/api/v1/policies/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("T-API"))
                .andExpect(jsonPath("$.supportContent").value("월 20만 원 지원"));
    }

    @Test
    void 없는_정책은_404와_사유를_돌려준다() throws Exception {
        mvc.perform(get("/api/v1/policies/0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("정책을 찾을 수 없습니다: 0"));
    }

    @Test
    void id가_숫자가_아니면_400과_사유를_돌려준다() throws Exception {
        mvc.perform(get("/api/v1/policies/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("정책 id 는 숫자여야 합니다."));
    }

    @Test
    void 상세_조회는_IP마다_1분에_30번까지_넘으면_429() throws Exception {
        Long id = jdbc.queryForObject("SELECT id FROM policy WHERE policy_no = 'T-API'", Long.class);

        for (int i = 0; i < PolicyDetailRateLimiter.MAX_PER_WINDOW; i++) {
            mvc.perform(get("/api/v1/policies/{id}", id).with(fromIp("203.0.113.50")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/policies/{id}", id).with(fromIp("203.0.113.50")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("잠시 후 다시 시도해 주세요. 정책 상세는 1분에 30번까지 볼 수 있습니다."));

        // 다른 사용자(IP)는 영향을 받지 않는다
        mvc.perform(get("/api/v1/policies/{id}", id).with(fromIp("203.0.113.60")))
                .andExpect(status().isOk());
    }

    /** 요청의 접속 주소를 바꾼다. 기본값 127.0.0.1 을 쓰는 다른 테스트와 횟수가 섞이지 않게 한다 */
    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
