package kr.hojun.policymatch.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.properties 의 myhome.api.* 값. 인증키 · 타임아웃은 공공데이터포털 공통 설정(data-go-kr.*)을 쓴다. */
@ConfigurationProperties(prefix = "myhome.api")
public class MyhomeProperties {

    /** 공공임대 모집공고 기능 (12/5 측정: 347행 · 공고 117개) */
    private String rentalUrl = "https://apis.data.go.kr/1613000/HWSPR02/rsdtRcritNtcList";

    /** 공공분양 모집공고 기능 (12/5 측정: 71행 · 공고 39개) */
    private String saleUrl = "https://apis.data.go.kr/1613000/HWSPR02/ltRsdtRcritNtcList";

    /** 한 번에 받을 행 수. 12/5 측정에서 100 으로 받았다. 기능별 일일 한도가 1,000번이라 넉넉하다 */
    private int numOfRows = 100;

    public String getRentalUrl() { return rentalUrl; }
    public void setRentalUrl(String rentalUrl) { this.rentalUrl = rentalUrl; }

    public String getSaleUrl() { return saleUrl; }
    public void setSaleUrl(String saleUrl) { this.saleUrl = saleUrl; }

    public int getNumOfRows() { return numOfRows; }
    public void setNumOfRows(int numOfRows) { this.numOfRows = numOfRows; }
}
