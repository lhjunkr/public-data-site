package kr.hojun.policymatch.collect;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.properties 의 cleaneye.api.* 값. 인증키 · 타임아웃은 공공데이터포털 공통 설정(data-go-kr.*)을 쓴다. */
@ConfigurationProperties(prefix = "cleaneye.api")
public class CleaneyeProperties {

    /** 지방공공기관 채용정보 기능 (XML 만 준다, 12/5 측정에 쓴 주소). */
    private String baseUrl = "https://apis.data.go.kr/B551982/openApiEmployInfo/openXmlEmployInfo";

    /**
     * 부를 시도 코드. 행정구역 코드가 아니라 클린아이 자체 코드다(서울 = 007001, 12/5 포털 미리보기 주소에서 확인).
     * 12/5: 007001 ~ 007017 중 007005 를 뺀 16개에 공고가 있었다(007005 는 통합 전 광주로 추정, 007018 ~ 020 은 결과 없음).
     * 결과 없는 코드도 부른다. 새 코드에 공고가 생기면 받아지고, 호출은 3번 더 늘 뿐이다(일일 한도 10,000번).
     */
    private List<String> sidoCodes = new ArrayList<>(List.of(
            "007001", "007002", "007003", "007004", "007005", "007006", "007007", "007008", "007009", "007010",
            "007011", "007012", "007013", "007014", "007015", "007016", "007017", "007018", "007019", "007020"));

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public List<String> getSidoCodes() { return sidoCodes; }
    public void setSidoCodes(List<String> sidoCodes) { this.sidoCodes = sidoCodes; }
}
