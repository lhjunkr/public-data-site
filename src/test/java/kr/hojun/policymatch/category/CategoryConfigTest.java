package kr.hojun.policymatch.category;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 실제 묶음표(application.yml)를 읽어 시험한다 (D-018).
 * 10/30 측정에서 나온 원본 중분류 19개가 모두 칩에 들어 있는지, API 가 닫힌 탭을 숨기는지 본다.
 */
@SpringBootTest
class CategoryConfigTest {

    /** 10/30 측정(scripts/category-distribution.sql)에서 나온 원본 중분류 이름 전부 */
    private static final List<String> MEDIUMS_2026_10_30 = List.of(
            "건강", "교육비지원", "권익보호", "기숙사", "문화활동", "문화활동 및 생활지원", "미래역량강화",
            "예술인지원", "온·오프라인교육", "온라인교육", "재직자", "전월세 및 주거급여 지원", "정책인프라구축",
            "주택 및 거주지", "창업", "청년국제교류", "청년참여", "취약계층 및 금융지원", "취업");

    @Autowired CategoryCatalog catalog;
    @Autowired WebApplicationContext context;

    @Test
    void 측정된_원본_중분류는_모두_묶음표에_있다() {
        for (String medium : MEDIUMS_2026_10_30) {
            assertTrue(catalog.unmappedOf(medium).isEmpty(), "묶음표에 없음: " + medium);
        }
    }

    @Test
    void 분류_API는_열린_탭만_화면_순서대로_준다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("housing"))
                .andExpect(jsonPath("$[0].chips[0].code").value("housing.rent"))
                .andExpect(jsonPath("$.length()").value(4))            // 일자리(닫힘) 제외 4개
                .andExpect(jsonPath("$[3].code").value("participation"));
    }
}
