package kr.hojun.policymatch.collect;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터포털(apis.data.go.kr) 공통 설정 — application.properties 의 data-go-kr.* 값.
 *
 * 공공데이터포털은 계정당 인증키가 하나이고 승인 · 트래픽만 API 별이다(12/5 확인).
 * 그래서 K-Startup · 마이홈 · 잡알리오 · 지방공공기관 채용 수집기가 이 키 하나(DATA_GO_KR_KEY)를 같이 쓴다.
 * 온통청년 키(YOUTH_API_KEY)는 다른 기관의 키라 따로다.
 */
@ConfigurationProperties(prefix = "data-go-kr")
public class DataGoKrProperties {

    /** "일반 인증키(Decoding)" 값. 요청할 때 우리가 한 번 인코딩한다. */
    private String key = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    /** n 번째 실패 뒤 n × 이 값만큼 기다렸다가 다시 시도한다. 테스트는 짧게 줄인다. */
    private Duration retryBaseDelay = Duration.ofSeconds(1);

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }
}
