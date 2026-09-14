package com.potner.device.dto;

import com.potner.device.domain.IotDeviceConnectionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 장치 관리 화면의 로봇 한 대다.
 *
 * <p>{@code connectionStatus}와 {@code lastSeenAt}은 저장된 컬럼이 아니라 하위 장치 상태에서
 * 파생한 값이다. {@code batteryPercent}와 {@code firmwareVersion}은 로봇 고유 데이터이며
 * 수집 연동 전까지 비어 있다.
 *
 * <p>{@code assignedPlantId}가 null 이면 아직 어느 식물도 담당하지 않는다. 이 상태에서는
 * 측정값이 저장되지 않으므로 앱이 배정을 유도해야 한다.
 */
public record RegisteredRobotResponse(
        String robotId,
        String deviceUid,
        String name,
        IotDeviceConnectionStatus connectionStatus,
        LocalDateTime lastSeenAt,
        Integer batteryPercent,
        String firmwareVersion,
        String assignedPlantId,
        String assignedPlantName,
        LocalDateTime assignedAt,
        List<IotDeviceResponse> devices
) {
    public RegisteredRobotResponse {
        devices = List.copyOf(devices);
    }
}
