package com.potner.command.application;

import com.potner.command.domain.DeviceCommandType;
import com.potner.location.domain.RobotLocationType;

import java.math.BigDecimal;

/**
 * 명령 행이 저장됐으니 MQTT 로 내보내라는 신호다.
 *
 * <p>발행을 트랜잭션 커밋 뒤로 미루는 것이 목적이다. 커밋 전에 브로커로 나가면 장치가 즉시
 * 회신했을 때(BUSY 는 몇 ms 만에 온다) 결과 처리기가 아직 없는 requestId 를 찾다 버린다.
 *
 * <p>{@code BloomRecordedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다. 좌표를
 * 발행 시점에 실어 두는 이유도 같다 — 수신자가 다시 조회하면 그 사이 좌표가 바뀌었을 때
 * 이력의 목적지와 실제 발행 좌표가 어긋난다.
 *
 * @param requestedMl WATER 만. 그 외 {@code null}
 * @param destination NAVIGATE 만. 그 외 {@code null}
 * @param runSeconds  FAN 만. 그 외 {@code null}
 */
public record DeviceCommandIssuedEvent(
        String requestId,
        String deviceUid,
        DeviceCommandType commandType,
        BigDecimal requestedMl,
        RobotLocationType destination,
        BigDecimal poseX,
        BigDecimal poseY,
        BigDecimal poseYaw,
        Integer runSeconds
) {
}
