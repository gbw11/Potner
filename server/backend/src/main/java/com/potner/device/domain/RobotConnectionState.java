package com.potner.device.domain;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 하위 장치 상태에서 파생한 로봇의 연결 상태다.
 *
 * <p>Heartbeat는 {@code iot_device}만 갱신하므로 {@code robot.connection_status} 컬럼은 생성 시
 * 기본값 'OFFLINE'에 멈춰 있다. 같은 사실을 두 곳에 저장하면 어긋날 수 있어 장치 상태를 유일한
 * 근거로 삼는다. 장치 하나라도 응답하고 있으면 로봇에 도달할 수 있다는 뜻이다.
 *
 * <p>식물별 장치 조회와 로봇 목록 조회가 같은 규칙을 쓰도록 한곳에 둔다.
 */
public record RobotConnectionState(
        IotDeviceConnectionStatus status,
        LocalDateTime lastSeenAt
) {
    public static RobotConnectionState derive(List<IotDevice> devices) {
        return new RobotConnectionState(deriveStatus(devices), deriveLastSeenAt(devices));
    }

    private static IotDeviceConnectionStatus deriveStatus(List<IotDevice> devices) {
        boolean anyOnline = devices.stream()
                .anyMatch(device -> device.getConnectionStatus() == IotDeviceConnectionStatus.ONLINE);
        return anyOnline
                ? IotDeviceConnectionStatus.ONLINE
                : IotDeviceConnectionStatus.OFFLINE;
    }

    /** 장치들이 마지막으로 응답한 시각 중 가장 최근값이다. */
    private static LocalDateTime deriveLastSeenAt(List<IotDevice> devices) {
        return devices.stream()
                .map(IotDevice::getLastSeenAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
