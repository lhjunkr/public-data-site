package kr.hojun.policymatch.collect;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.properties 의 youth.api.* 값을 담는 설정 객체. */
@ConfigurationProperties(prefix = "youth.api")
public class YouthApiProperties {

    private String baseUrl = "https://www.youthcenter.go.kr/go/ythip/getPlcy";
    private String key = "";
    private int pageSize = 100;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
