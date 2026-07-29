package com.potner.device.dto;

import com.potner.device.domain.IotDeviceConnectionStatus;
import com.potner.device.domain.RobotState;

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

        /**
         * 젯슨이 알려 준 현재 행동 상태다. 상태를 한 번도 받지 못했으면 {@code IDLE} 이다.
         *
         * <p>{@code SERVICING} 이 급수와 송풍을 함께 가리킨다. 서버는 둘을 구별할 수 없으므로
         * 앱이 문구를 만들 때도 하나로 묶어야 한다.
         *
         * <p>{@code IDLE} 은 어디에서 대기하는지 말해 주지 않는다. 대기 장소인지 급수
         * 스테이션인지는 이 값으로 알 수 없다.
         */
        RobotState currentState,

        /** 현재 상태로 바뀐 시각(UTC). 같은 상태가 반복 수신되는 동안에는 갱신되지 않는다. */
        LocalDateTime stateChangedAt,

        LocalDateTime assignedAt
) {
}
