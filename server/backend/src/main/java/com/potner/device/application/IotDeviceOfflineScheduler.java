package com.potner.device.application;

import com.potner.mqtt.config.MqttHeartbeatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "potner.mqtt", name = "enabled", havingValue = "true")
public class IotDeviceOfflineScheduler {

    private static final Logger log = LoggerFactory.getLogger(IotDeviceOfflineScheduler.class);

    private final IotDeviceHeartbeatService heartbeatService;
    private final MqttHeartbeatProperties properties;
    private final Clock clock;

    public IotDeviceOfflineScheduler(
            IotDeviceHeartbeatService heartbeatService,
            MqttHeartbeatProperties properties,
            Clock clock
    ) {
        this.heartbeatService = heartbeatService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${potner.mqtt.heartbeat.offline-check-interval-seconds}",
            timeUnit = TimeUnit.SECONDS
    )
    public void markTimedOutDevicesOffline() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minusSeconds(properties.offlineTimeoutSeconds()),
                ZoneOffset.UTC
        );
        int updatedDevices = heartbeatService.markTimedOutDevicesOffline(cutoff);
        if (updatedDevices > 0) {
            log.info(
                    "IoT devices marked OFFLINE: count={}, timeoutSeconds={}",
                    updatedDevices,
                    properties.offlineTimeoutSeconds()
            );
        }
    }
}
