package com.potner.device.dto;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceConnectionStatus;
import com.potner.device.domain.IotDeviceType;

import java.time.LocalDateTime;

public record IotDeviceResponse(
        String deviceUid,
        IotDeviceType deviceType,
        IotDeviceConnectionStatus connectionStatus,
        LocalDateTime lastSeenAt
) {
    public static IotDeviceResponse from(IotDevice device) {
        return new IotDeviceResponse(
                device.getDeviceUid(),
                device.getDeviceType(),
                device.getConnectionStatus(),
                device.getLastSeenAt()
        );
    }
}
