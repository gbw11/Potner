package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
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

/**
 * 회신 없는 명령을 끊는다.
 *
 * <p>장치가 죽으면 회신이 영영 없다. 그대로 두면 ISSUED 가 남아 같은 종류의 다음 명령이
 * 계속 409 로 막힌다 — 죽은 장치 하나가 급수를 영구히 잠그는 셈이다.
 *
 * <p>{@code IotDeviceOfflineScheduler} 와 같은 방침으로 MQTT 가 켜진 환경에서만 돈다.
 * 브로커가 없으면 명령이 발행될 일도 없다.
 */
@Component
@ConditionalOnProperty(prefix = "potner.mqtt", name = "enabled", havingValue = "true")
public class DeviceCommandTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandTimeoutScheduler.class);

    private final DeviceCommandRepository commandRepository;
    private final DeviceCommandProperties properties;
    private final Clock clock;

    public DeviceCommandTimeoutScheduler(
            DeviceCommandRepository commandRepository,
            DeviceCommandProperties properties,
            Clock clock
    ) {
        this.commandRepository = commandRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${potner.device-command.timeout-check-interval-seconds}",
            timeUnit = TimeUnit.SECONDS
    )
    @Transactional
    public void markTimedOutCommands() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minusSeconds(properties.timeoutSeconds()),
                ZoneOffset.UTC
        );
        int updated = commandRepository.markIssuedTimedOutBefore(
                cutoff,
                DeviceCommandStatus.ISSUED,
                DeviceCommandStatus.TIMED_OUT
        );
        if (updated > 0) {
            log.warn(
                    "Device commands marked TIMED_OUT: count={}, timeoutSeconds={}",
                    updated,
                    properties.timeoutSeconds()
            );
        }
    }
}
