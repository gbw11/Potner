package com.potner.device.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class IotDeviceTest {

    @Test
    void heartbeatSetsStatusOnlineAndUpdatesLastSeenAt() {
        IotDevice device = new IotDevice();
        LocalDateTime receivedAt = LocalDateTime.parse("2026-07-23T05:30:00");

        device.recordHeartbeat(receivedAt);

        assertThat(device.getConnectionStatus()).isEqualTo(IotDeviceConnectionStatus.ONLINE);
        assertThat(device.getLastSeenAt()).isEqualTo(receivedAt);
    }
}
