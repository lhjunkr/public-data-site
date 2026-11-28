package kr.hojun.policymatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import kr.hojun.policymatch.alert.FakeAlertSender;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.PolicyCollector;
import kr.hojun.policymatch.collect.Source;
import kr.hojun.policymatch.collect.UnknownSourceException;
import kr.hojun.policymatch.normalize.NormalizeResult;
import kr.hojun.policymatch.normalize.PolicyNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 출처별 매일 갱신 예약 (12/16). 스프링을 띄우지 않고 설정 → 예약 목록 변환과 등록만 확인한다.
 */
class SchedulingConfigTest {

    @Test
    void 설정한_출처마다_예약을_하나씩_만들고_시각_순으로_놓는다() {
        Map<String, String> crons = new LinkedHashMap<>();
        crons.put("alio", "0 30 4 * * *");
        crons.put("youth", "0 10 4 * * *");

        List<SchedulingConfig.Planned> plan = SchedulingConfig.plan(crons);

        assertEquals(List.of(Source.YOUTH, Source.ALIO), plan.stream().map(SchedulingConfig.Planned::source).toList());
        assertEquals("0 10 4 * * *", plan.get(0).cron());
    }

    @Test
    void 대시로_둔_출처는_예약하지_않는다() {
        // 한 출처만 잠시 빼고 싶을 때 설정 한 줄로 끈다
        Map<String, String> crons = Map.of("youth", "0 10 4 * * *", "cleaneye", "-");

        assertEquals(List.of(Source.YOUTH),
                SchedulingConfig.plan(crons).stream().map(SchedulingConfig.Planned::source).toList());
    }

    @Test
    void 없는_출처_코드면_앱이_뜨지_않는다() {
        // 오타(aliio)를 조용히 건너뛰면 그 출처는 아무도 모르게 매일 갱신에서 빠진다
        assertThrows(UnknownSourceException.class, () -> SchedulingConfig.plan(Map.of("aliio", "0 30 4 * * *")));
    }

    @Test
    void 읽을_수_없는_cron_이면_앱이_뜨지_않는다() {
        // 스프링 cron 은 초부터 6칸이다. 리눅스 crontab 처럼 5칸으로 쓰면 실패한다
        assertThrows(IllegalArgumentException.class, () -> SchedulingConfig.plan(Map.of("alio", "30 4 * * *")));
    }

    @Test
    void 운영_설정은_5개_출처를_서로_다른_시각에_DB_백업_전에_돌린다() throws IOException {
        // 잠금이 하나라 같은 시각이면 뒤의 것이 건너뛴다. 의견 정리(04:30)와도 겹치지 않고, DB 백업(04:40) 전에 끝낸다
        Properties props = applicationProperties();
        List<SchedulingConfig.Planned> plan = SchedulingConfig.plan(cronsIn(props));
        int purge = new SchedulingConfig.Planned(Source.YOUTH, props.getProperty("feedback.purge-cron")).secondOfDay();

        assertEquals(Set.of(Source.values()), new HashSet<>(plan.stream().map(SchedulingConfig.Planned::source).toList()),
                "모든 출처가 매일 갱신된다");
        Set<Integer> seconds = new HashSet<>();
        for (SchedulingConfig.Planned p : plan) {
            assertTrue(seconds.add(p.secondOfDay()), "시각이 겹친다: " + p);
            assertTrue(p.secondOfDay() != purge, "의견 정리와 시각이 겹친다: " + p);
            assertTrue(p.secondOfDay() < LocalTime.of(4, 40).toSecondOfDay(), "DB 백업(04:40) 전이어야 한다: " + p);
        }
    }

    @Test
    void 켜져_있으면_출처마다_cron_작업을_등록하고_그_출처를_돌린다() {
        BatchScheduleProperties props = new BatchScheduleProperties();
        props.setCrons(new LinkedHashMap<>(Map.of("youth", "0 10 4 * * *", "alio", "0 30 4 * * *")));
        RecordingBatch batch = new RecordingBatch();
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new SchedulingConfig(props, new DailyRefreshJob(batch, new FakeAlertSender())).configureTasks(registrar);

        List<CronTask> tasks = registrar.getCronTaskList();
        assertEquals(List.of("0 10 4 * * *", "0 30 4 * * *"), tasks.stream().map(CronTask::getExpression).toList());
        tasks.get(1).getRunnable().run();
        assertEquals(List.of(Source.ALIO), batch.called, "두 번째 작업은 잡알리오를 돌린다");
    }

    @Test
    void 꺼져_있으면_아무것도_등록하지_않는다() {
        BatchScheduleProperties props = new BatchScheduleProperties();
        props.setEnabled(false);
        props.setCrons(new LinkedHashMap<>(Map.of("youth", "0 10 4 * * *")));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new SchedulingConfig(props, new DailyRefreshJob(new RecordingBatch(), new FakeAlertSender())).configureTasks(registrar);

        assertEquals(0, registrar.getCronTaskList().size());
    }

    private static Properties applicationProperties() throws IOException {
        Properties p = new Properties();
        try (InputStream in = SchedulingConfigTest.class.getResourceAsStream("/application.properties");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }

    private static Map<String, String> cronsIn(Properties p) {
        Map<String, String> crons = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("batch.crons.")) {
                crons.put(key.substring("batch.crons.".length()), p.getProperty(key));
            }
        }
        return crons;
    }

    /** 어느 출처로 불렸는지만 적는다 */
    private static class RecordingBatch extends PolicyBatch {
        final List<Source> called = new ArrayList<>();

        RecordingBatch() {
            super(List.of(new PolicyCollector(null, null, null, null)), List.of(new PolicyNormalizer(null, null, null, null)));
        }

        @Override
        public RefreshResult refresh(Source source) {
            called.add(source);
            return new RefreshResult(new CollectResult(1, 1, 1, 1), new NormalizeResult(1, 1, 0, 0, 0, 0, 0, 0, 0, 0), 1);
        }
    }
}
