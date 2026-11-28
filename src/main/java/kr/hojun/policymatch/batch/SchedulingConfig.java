package kr.hojun.policymatch.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hojun.policymatch.collect.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 예약 작업을 켜고(D-020), 출처별 매일 갱신을 설정대로 예약한다(12/16).
 *
 *  - 출처마다 시각을 따로 둔다(batch.crons.출처=cron). 수집 · 정규화 잠금은 하나라(PolicyBatch) 같은 시각이면 뒤의 것이 건너뛴다.
 *    그래서 시각을 겹치지 않게 띄운다. 한 출처가 실패해도 다른 출처는 제 시각에 따로 돈다.
 *  - 새 출처를 매일 갱신에 넣을 때는 설정 한 줄만 더한다. 코드는 고치지 않는다.
 *  - 설정이 잘못되면(없는 출처 코드, 읽을 수 없는 cron) 앱이 뜰 때 실패한다. 조용히 그 출처만 매일 갱신이 빠지는 것보다 낫다.
 *  - 시간대를 Asia/Seoul 로 못 박는다. 서버 시간대 설정이 바뀌어도 한국 시간 기준으로 돈다.
 *  - 스프링 기본 스케줄러는 스레드 1개다. 출처별 작업과 의견 정리(D-024, 04:30)가 한 줄로 차례로 돈다.
 *    가장 긴 온통청년도 2분 안이라(실측) 시각을 몇 분씩 띄워 두면 서로 밀리지 않는다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BatchScheduleProperties props;
    private final DailyRefreshJob job;

    public SchedulingConfig(BatchScheduleProperties props, DailyRefreshJob job) {
        this.props = props;
        this.job = job;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!props.isEnabled()) {
            log.info("매일 갱신 예약 꺼짐 (batch.enabled=false)");
            return;
        }
        List<Planned> plan = plan(props.getCrons());
        for (Planned p : plan) {
            registrar.addCronTask(new CronTask(() -> job.run(p.source()), p.trigger()));
        }
        log.info("매일 갱신 예약 — {}", plan.stream()
                .map(p -> p.source().code() + " " + p.cron()).collect(Collectors.joining(" · ")));
    }

    /**
     * 설정(출처 코드 → cron)을 예약 목록으로 바꾼다. "-" 인 출처는 뺀다. 다음 실행 시각 순으로 정렬한다.
     * 없는 출처 코드면 UnknownSourceException, 읽을 수 없는 cron 이면 IllegalArgumentException 으로 앱이 뜨지 않는다.
     */
    static List<Planned> plan(Map<String, String> crons) {
        Map<Source, String> bySource = new EnumMap<>(Source.class);
        for (Map.Entry<String, String> e : crons.entrySet()) {
            Source source = Source.fromCode(e.getKey().strip());
            String cron = e.getValue() == null ? "-" : e.getValue().strip();
            if (cron.equals("-") || cron.isEmpty()) {
                continue;
            }
            CronExpression.parse(cron);
            bySource.put(source, cron);
        }
        List<Planned> plan = new ArrayList<>();
        bySource.forEach((source, cron) -> plan.add(new Planned(source, cron)));
        plan.sort(Comparator.comparing(Planned::secondOfDay));
        return plan;
    }

    /** 출처 하나의 예약. */
    record Planned(Source source, String cron) {

        CronTrigger trigger() {
            return new CronTrigger(cron, SEOUL);
        }

        /** 정렬 · 겹침 확인용: 하루 중 몇 초째에 도는가(자정 직후 첫 실행 기준). */
        int secondOfDay() {
            LocalDateTime next = CronExpression.parse(cron).next(LocalDate.of(2026, 1, 5).atStartOfDay());
            return next == null ? Integer.MAX_VALUE : next.toLocalTime().toSecondOfDay();
        }
    }
}
