package kr.hojun.policymatch.batch;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 를 켠다 (D-020). 스프링 기본 스케줄러는 스레드 1개로 돈다.
 * 예약 작업이 매일 갱신 하나뿐이라 충분하다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
