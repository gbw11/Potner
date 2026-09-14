package com.potner.arrival.application;

import com.potner.arrival.config.ArrivalProperties;
import com.potner.arrival.domain.ArrivalEventRepository;
import com.potner.arrival.domain.ArrivalProcessingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "potner.mqtt", name = "enabled", havingValue = "true")
public class ArrivalTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArrivalTimeoutScheduler.class);

    private final ArrivalEventRepository arrivalEventRepository;
    private final ArrivalProperties properties;
    private final Clock clock;

    public ArrivalTimeoutScheduler(
            ArrivalEventRepository arrivalEventRepository,
            ArrivalProperties properties,
            Clock clock
    ) {
        this.arrivalEventRepository = arrivalEventRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${potner.arrival.timeout-check-interval-seconds}",
            timeUnit = TimeUnit.SECONDS
    )
    @Transactional
    public void markTimedOutEvents() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minusSeconds(properties.commandTimeoutSeconds()),
                ZoneOffset.UTC
        );
        int updated = arrivalEventRepository.markPublishedTimedOutBefore(
                cutoff,
                ArrivalProcessingStatus.COMMAND_PUBLISHED,
                ArrivalProcessingStatus.TIMED_OUT
        );
        if (updated > 0) {
            log.warn(
                    "Arrival events marked TIMED_OUT: count={}, timeoutSeconds={}",
                    updated,
                    properties.commandTimeoutSeconds()
            );
        }
    }
}
