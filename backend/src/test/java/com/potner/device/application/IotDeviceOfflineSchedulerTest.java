package com.potner.device.application;

import com.potner.mqtt.config.MqttHeartbeatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IotDeviceOfflineSchedulerTest {

    @Test
    void schedulerUsesConfiguredTimeoutToMarkTimedOutDevicesOffline() {
        IotDeviceHeartbeatService service = mock(IotDeviceHeartbeatService.class);
        MqttHeartbeatProperties properties = new MqttHeartbeatProperties(
                "potner/device/+/status/heartbeat",
                90,
                30
        );
        IotDeviceOfflineScheduler scheduler = new IotDeviceOfflineScheduler(
                service,
                properties,
                Clock.fixed(Instant.parse("2026-07-23T05:30:00Z"), ZoneOffset.UTC)
        );

        scheduler.markTimedOutDevicesOffline();

        verify(service).markTimedOutDevicesOffline(
                LocalDateTime.parse("2026-07-23T05:28:30")
        );
    }

    @Test
    void schedulerIntervalComesFromConfiguration() throws NoSuchMethodException {
        Method method = IotDeviceOfflineScheduler.class.getMethod(
                "markTimedOutDevicesOffline"
        );
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${potner.mqtt.heartbeat.offline-check-interval-seconds}");
        assertThat(scheduled.timeUnit()).isEqualTo(TimeUnit.SECONDS);
    }
}
