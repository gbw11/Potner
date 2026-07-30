package com.potner.command.dto;

import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.location.domain.RobotLocationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 명령 한 건의 현재 상태다.
 *
 * <p>발행 직후에는 {@code status=ISSUED} 다. 명령은 비동기라 발행 응답이 수행 결과를 담을 수
 * 없고, 앱은 목록을 다시 조회해 결과를 본다.
 *
 * @param dispensedMl 실제 급수량. 요청량과 다를 수 있다(펌프 상한 도달 등)
 * @param destination NAVIGATE 만. 그 외 {@code null}
 * @param runSeconds  FAN 만. 그 외 {@code null}
 */
public record DeviceCommandResponse(
        String requestId,
        DeviceCommandType type,
        DeviceCommandStatus status,
        BigDecimal requestedMl,
        RobotLocationType destination,
        Integer runSeconds,
        BigDecimal dispensedMl,
        String errorMessage,
        LocalDateTime issuedAt,
        LocalDateTime reportedAt
) {

    public static DeviceCommandResponse from(DeviceCommand command) {
        return new DeviceCommandResponse(
                command.getRequestId(),
                command.getCommandType(),
                command.getStatus(),
                command.getRequestedMl(),
                command.getDestination(),
                command.getRunSeconds(),
                command.getDispensedMl(),
                command.getErrorMessage(),
                command.getIssuedAt(),
                command.getReportedAt()
        );
    }
}
