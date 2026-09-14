package com.potner.light.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하루가 마감된 뒤 전일분을 집계한다.
 *
 * <p>자정 직후에 돌리면 늦게 도착한 측정값을 놓치므로 서비스 타임존 기준 새벽에 실행한다.
 * 서버가 꺼져 있던 날은 건너뛴다. 지난 날짜를 메우는 작업은 별도로 다룬다.
 */
@Component
@ConditionalOnProperty(prefix = "potner.daily-light", name = "enabled", havingValue = "true")
public class DailyLightScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyLightScheduler.class);

    private final DailyLightAggregationService aggregationService;

    public DailyLightScheduler(DailyLightAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @Scheduled(cron = "${potner.daily-light.cron}", zone = "${potner.daily-light.zone}")
    public void aggregatePreviousDay() {
        int processed = aggregationService.aggregatePreviousDayForActivePlants();
        if (processed > 0) {
            log.info("Daily light aggregated: plants={}", processed);
        }
    }
}
