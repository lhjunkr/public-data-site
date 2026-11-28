package kr.hojun.policymatch.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 10/30 측정에서 나온 원본 중분류 19개가 모두 칩에 들어 있는지, API 가 열린 탭을 화면 순서대로 주는지 본다.
 * 측정 19개는 온통청년 공식 중분류 17개(API코드정보.xlsx)에 이름이 바뀐 2개를 더한 것이다.
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
                .andExpect(jsonPath("$.length()").value(5))            // D-021 로 일자리까지 5개 모두 열림
                .andExpect(jsonPath("$[3].code").value("participation"))
                .andExpect(jsonPath("$[4].code").value("jobs"));
    }

    @Test
    void 공공기관_채용_탭은_화면_편입_전까지_닫혀_있고_칩은_있다() throws Exception {
        // 12/13: 잡알리오 데이터부터 넣는다. 탭이 닫혀 있으면 분류 API 와 매칭 결과에 나오지 않는다(12/15 에 연다)
        assertTrue(catalog.allChipCodes().containsAll(List.of(
                "recruit.regular", "recruit.indefinite", "recruit.intern", "recruit.contract", "recruit.entry")));
        assertTrue(catalog.openChipCodes().stream().noneMatch(code -> code.startsWith("recruit.")));
        assertEquals(List.of("recruit.intern"), List.copyOf(catalog.chipsOf("청년인턴(체험형),청년인턴(채용형)")),
                "체험형 · 채용형은 같은 청년인턴 칩");

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.code == 'recruit')]").isEmpty());
    }

    @Test
    void 주거_탭의_세_번째_칩은_마이홈_공공임대_분양이다() throws Exception {
        // 12/12: 원본 중분류가 없는 칩(mediums: [])도 앱이 뜨고 화면 API 에 나온다
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chips.length()").value(3))
                .andExpect(jsonPath("$[0].chips[2].code").value("housing.public"))
                .andExpect(jsonPath("$[0].chips[2].name").value("공공임대·분양"));
    }
}
