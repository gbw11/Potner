package com.potner.device.dto;

import com.potner.device.domain.IotDeviceConnectionStatus;

import java.time.LocalDateTime;

/**
 * 로봇 요약이다.
 *
 * <p>{@code connectionStatus}와 {@code lastSeenAt}은 저장된 컬럼이 아니라 하위 장치 상태에서
 * 계산한 값이다. {@code batteryPercent}와 {@code firmwareVersion}은 로봇 고유 데이터이며
 * 수집 연동 전까지 비어 있다.
 */
public record RobotSummaryResponse(
        String robotId,
        String name,
        IotDeviceConnectionStatus connectionStatus,
        LocalDateTime lastSeenAt,
        Integer batteryPercent,
        String firmwareVersion,
        LocalDateTime assignedAt
) {
}
