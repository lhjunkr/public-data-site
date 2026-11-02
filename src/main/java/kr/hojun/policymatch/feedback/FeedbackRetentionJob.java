package kr.hojun.policymatch.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간(90일)이 지난 답장용 이메일을 매일 새벽 지운다 (D-024).
 * 답장을 마친 의견의 이메일은 처리 완료 때 이미 지워진다. 이 작업은 답장하지 못하고 남은 것을 정리한다.
 * 시각은 feedback.purge-cron (기본 끔, 운영은 04:30 — 04:10 매일 갱신이 끝난 뒤).
 */
@Component
public class FeedbackRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRetentionJob.class);

    private final FeedbackStore store;

    public FeedbackRetentionJob(FeedbackStore store) {
        this.store = store;
    }

    @Scheduled(cron = "${feedback.purge-cron:-}", zone = "Asia/Seoul")
    public void purge() {
        try {
            int n = store.purgeExpiredEmails();
            log.info("의견 이메일 보관 기간 정리 — {}건 삭제", n);
        } catch (RuntimeException e) {
            log.error("의견 이메일 보관 기간 정리 실패 — 내일 다시 시도합니다", e);
        }
    }
}
