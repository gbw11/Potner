package com.potner.device.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceConnectionStatus;
import com.potner.device.domain.IotDeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class IotDeviceHeartbeatService {

    private final IotDeviceRepository iotDeviceRepository;
    private final Clock clock;

    public IotDeviceHeartbeatService(IotDeviceRepository iotDeviceRepository, Clock clock) {
        this.iotDeviceRepository = iotDeviceRepository;
        this.clock = clock;
    }

    @Transactional
    public HeartbeatUpdateResult recordHeartbeat(String deviceUid) {
        IotDevice device = iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull(deviceUid).orElse(null);
        if (device == null) {
            return HeartbeatUpdateResult.DEVICE_NOT_FOUND;
        }

        LocalDateTime receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        device.recordHeartbeat(receivedAt);
        return HeartbeatUpdateResult.UPDATED;
    }

    @Transactional
    public int markTimedOutDevicesOffline(LocalDateTime cutoff) {
        return iotDeviceRepository.markOnlineDevicesOfflineBefore(
                cutoff,
                IotDeviceConnectionStatus.ONLINE,
                IotDeviceConnectionStatus.OFFLINE
        );
    }
}
