package kr.hojun.policymatch.batch;

import kr.hojun.policymatch.alert.AlertSender;
import kr.hojun.policymatch.normalize.MassDeactivationException;
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
 *  - 대량 비활성으로 멈춘 경우(D-019 "12/8 추가")는 따로 알린다. 사람이 원본을 확인하고 강제 반영할지 정해야 하므로
 *    건수와 확인 방법을 함께 보낸다.
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
        } catch (MassDeactivationException e) {
            log.error("매일 갱신 — 대량 비활성 감지로 정규화를 반영하지 않음", e);
            alert.send(massDeactivationAlert(e));
        } catch (RuntimeException e) {
            log.error("매일 갱신 실패 — 마지막 성공 데이터로 계속 서비스합니다", e);
            alert.send("[chajaga] 매일 갱신 실패 — 마지막 성공 데이터로 계속 서비스 중입니다.\n" + summary(e));
        }
    }

    /** 대량 비활성 알림. 건수 · 비율 · 상한과 사람이 할 일을 적는다. 정책 번호 목록은 넣지 않는다(서버 로그에 있음). */
    static String massDeactivationAlert(MassDeactivationException e) {
        return "[chajaga] 대량 비활성 감지 — 오늘 정규화를 반영하지 않았습니다. 화면은 마지막 반영 데이터로 계속 서비스 중입니다.\n"
                + "출처 " + e.source().code() + " · 비활성 예정 " + e.toDeactivate() + "건 / 활성 " + e.activeBefore()
                + "건 (" + e.percent() + "%) · 상한 " + e.limit() + "건\n"
                + "원본 사이트에서 실제로 사라졌는지 확인하고, 맞으면 관리 API 로 강제 반영: "
                + "POST /api/v1/admin/normalize?force=true";
    }

    /**
     * 알림은 외부(디스코드)로 나가므로 원인 설명을 그대로 보내지 않는다.
     * 연결 오류 메시지에는 요청 주소가 통째로 들어 있어 온통청년 인증키(apiKeyNm=…)가 섞인다. 그 값을 가리고 길이를 줄인다.
     * 공공데이터포털 키(serviceKey=…)는 DataGoKrClient 가 예외를 만들 때 이미 가리지만, 12/16 매일 갱신에 붙기 전에 여기서도 한 번 더 가린다.
     */
    static String summary(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        text = text.replaceAll("apiKeyNm=[^&\"\\s]+", "apiKeyNm=***");
        text = text.replaceAll("serviceKey=[^&\"\\s]+", "serviceKey=***");
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX) + "…";
    }
}
