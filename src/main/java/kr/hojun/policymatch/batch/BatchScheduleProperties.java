package kr.hojun.policymatch.batch;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 매일 갱신 예약 (D-020, 12/16 출처별). application.properties 의 batch.* 값.
 *
 *  - enabled: false 면 아무것도 예약하지 않는다(테스트는 build.gradle 에서 끈다).
 *  - crons: 출처 코드 → cron(초 분 시 일 월 요일, 한국 시간). "-" 이면 그 출처는 매일 갱신에서 뺀다.
 *    예: batch.crons.alio=0 30 4 * * *
 */
@ConfigurationProperties(prefix = "batch")
public class BatchScheduleProperties {

    private boolean enabled = true;
    private Map<String, String> crons = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, String> getCrons() { return crons; }
    public void setCrons(Map<String, String> crons) { this.crons = crons; }
}
