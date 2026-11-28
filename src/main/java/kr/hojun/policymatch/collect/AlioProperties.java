package kr.hojun.policymatch.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.properties 의 alio.api.* 값. 인증키 · 타임아웃은 공공데이터포털 공통 설정(data-go-kr.*)을 쓴다. */
@ConfigurationProperties(prefix = "alio.api")
public class AlioProperties {

    /** 공공기관 채용정보 목록 기능 (재정경제부_공공기관 채용정보 조회서비스, 12/5 측정에 쓴 주소). */
    private String baseUrl = "https://apis.data.go.kr/1051000/recruitment/list";
    private int numOfRows = 100;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getNumOfRows() { return numOfRows; }
    public void setNumOfRows(int numOfRows) { this.numOfRows = numOfRows; }
}
