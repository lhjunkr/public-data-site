package kr.hojun.policymatch.batch;

import kr.hojun.policymatch.alert.AlertSender;
import kr.hojun.policymatch.normalize.NormalizeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 자동 갱신 (D-020). 한국 시간 새벽에 수집 → 성공하면 정규화를 돌린다.
 *
 *  - 시각은 batch.daily-cron 설정이 정한다. 값이 없거나 "-" 이면 돌지 않는다 (테스트는 build.gradle 에서 끈다).
 *  - 시간대를 Asia/Seoul 로 못 박는다. 서버 시간대 설정이 바뀌어도 한국 시간 기준으로 돈다.
 *  - 실패해도 예외를 밖으로 던지지 않고 로그만 남긴다. 다음 날 다시 시도하고,
 *    그동안 화면은 마지막 성공 데이터를 보여 준다(D-017). 로그는 "매일 갱신"으로 찾는다.
 *  - 실패하면 휴대폰으로 알린다(D-026). 성공 · 건너뜀은 사람이 할 일이 없어 알리지 않는다.
 */
@Component
public class DailyRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(DailyRefreshJob.class);

    /** 알림에 넣는 원인 설명의 최대 길이. 전체 내용은 서버 로그에 있다 */
    private static final int SUMMARY_MAX = 300;

    private final PolicyBatch batch;
    private final AlertSender alert;

    public DailyRefreshJob(PolicyBatch batch, AlertSender alert) {
        this.batch = batch;
        this.alert = alert;
    }

    @Scheduled(cron = "${batch.daily-cron:-}", zone = "Asia/Seoul")
    public void run() {
        try {
            RefreshResult r = batch.refresh();
            NormalizeResult n = r.normalize();
            log.info("매일 갱신 완료 — {}초 · 수집 {}/{}건 · 적재 {}건 · 실패 {}건 · 비활성 {}건",
                    r.elapsedSeconds(), r.collect().itemsSeen(), r.collect().totCount(),
                    n.policies(), n.failed(), n.deactivated());
        } catch (BatchBusyException e) {
            log.warn("매일 갱신 건너뜀 — {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("매일 갱신 실패 — 마지막 성공 데이터로 계속 서비스합니다", e);
            alert.send("[chajaga] 매일 갱신 실패 — 마지막 성공 데이터로 계속 서비스 중입니다.\n" + summary(e));
        }
    }

    /**
     * 알림은 외부(디스코드)로 나가므로 원인 설명을 그대로 보내지 않는다.
     * 연결 오류 메시지에는 요청 주소가 통째로 들어 있어 온통청년 인증키(apiKeyNm=…)가 섞인다. 그 값을 가리고 길이를 줄인다.
     */
    static String summary(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        text = text.replaceAll("apiKeyNm=[^&\"\\s]+", "apiKeyNm=***");
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX) + "…";
    }
}
