package kr.hojun.policymatch.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.properties 의 kstartup.api.* 값. 인증키 · 타임아웃은 공공데이터포털 공통 설정(data-go-kr.*)을 쓴다. */
@ConfigurationProperties(prefix = "kstartup.api")
public class KstartupProperties {

    /** 지원사업 공고 기능. Service02 는 기능 이름 끝도 02 다(…01 은 옛 버전, 12/5). */
    private String baseUrl = "https://apis.data.go.kr/B552735/kisedKstartupService02/getAnnouncementInformation02";
    private int perPage = 100;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getPerPage() { return perPage; }
    public void setPerPage(int perPage) { this.perPage = perPage; }
}
