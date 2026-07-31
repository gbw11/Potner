package com.potner.device.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceConnectionStatus;
import com.potner.device.domain.IotDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IotDeviceHeartbeatServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T05:30:00Z");

    private IotDeviceRepository repository;
    private IotDevice device;
    private IotDeviceHeartbeatService service;

    @BeforeEach
    void setUp() {
        repository = mock(IotDeviceRepository.class);
        device = mock(IotDevice.class);
        service = new IotDeviceHeartbeatService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void heartbeatMarksExistingDeviceOnlineAtServerReceivedTime() {
        when(repository.findByDeviceUidAndReleasedAtIsNull("raspberry-01")).thenReturn(Optional.of(device));

        assertThat(service.recordHeartbeat("raspberry-01"))
                .isEqualTo(HeartbeatUpdateResult.UPDATED);

        verify(device).recordHeartbeat(LocalDateTime.parse("2026-07-23T05:30:00"));
    }

    @Test
    void unknownDeviceIsNotUpdated() {
        when(repository.findByDeviceUidAndReleasedAtIsNull("unknown")).thenReturn(Optional.empty());

        assertThat(service.recordHeartbeat("unknown"))
                .isEqualTo(HeartbeatUpdateResult.DEVICE_NOT_FOUND);

        verify(device, never()).recordHeartbeat(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marksOnlyTimedOutOnlineDevicesOfflineThroughRepository() {
        LocalDateTime cutoff = LocalDateTime.parse("2026-07-23T05:28:30");
        when(repository.markOnlineDevicesOfflineBefore(
                cutoff,
                IotDeviceConnectionStatus.ONLINE,
                IotDeviceConnectionStatus.OFFLINE
        )).thenReturn(2);

        assertThat(service.markTimedOutDevicesOffline(cutoff)).isEqualTo(2);
    }
}
