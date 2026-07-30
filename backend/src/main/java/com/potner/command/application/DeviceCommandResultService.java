package com.potner.command.application;

import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.CommandResultMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 결과 회신을 명령 행에 반영한다.
 *
 * <p>검증 순서가 중요하다. 중복(재전송)을 상태 검사보다 먼저 봐야 한다 — QoS 1 재전송이
 * ALREADY_COMPLETED 로 찍히면 "다른 회신이 두 번 왔다" 는 가짜 신호가 남는다.
 *
 * <p>{@code TIMED_OUT} 위에는 덮어쓴다. 타임아웃은 서버의 추정이고 회신은 물리적 사실이다.
 * 실제 급수량이 일기에 실리므로 늦은 회신을 버리면 기록이 틀어진다.
 */
@Service
public class DeviceCommandResultService {

    private final DeviceCommandRepository commandRepository;
    private final Clock clock;

    public DeviceCommandResultService(DeviceCommandRepository commandRepository, Clock clock) {
        this.commandRepository = commandRepository;
        this.clock = clock;
    }

    @Transactional
    public CommandResultApplyOutcome apply(
            String topicDeviceUid,
            DeviceCommandType topicCommandType,
            CommandResultMessage message
    ) {
        DeviceCommand command = commandRepository.findById(message.requestId()).orElse(null);
        if (command == null) {
            return CommandResultApplyOutcome.UNKNOWN_REQUEST;
        }
        if (!command.getDeviceUid().equals(topicDeviceUid)) {
            return CommandResultApplyOutcome.DEVICE_MISMATCH;
        }
        if (command.getCommandType() != topicCommandType) {
            return CommandResultApplyOutcome.TYPE_MISMATCH;
        }
        if (message.messageId().toString().equals(command.getResultMessageId())) {
            return CommandResultApplyOutcome.DUPLICATE;
        }
        if (command.getStatus().isTerminal()) {
            return CommandResultApplyOutcome.ALREADY_COMPLETED;
        }

        Optional<DeviceCommandStatus> reported =
                DeviceCommandStatus.fromDeviceReport(message.status());
        if (reported.isEmpty()) {
            return CommandResultApplyOutcome.INVALID_STATUS;
        }

        command.applyResult(
                reported.get(),
                message.dispensedMl(),
                errorMessage(message),
                message.messageId().toString(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        );
        return CommandResultApplyOutcome.APPLIED;
    }

    /** 촬영 실패는 코드가, 급수 실패는 문구가 온다. 코드가 있으면 앞에 붙여 진단을 돕는다. */
    private String errorMessage(CommandResultMessage message) {
        if (message.code() != null && !message.code().isBlank()) {
            return message.error() == null
                    ? message.code()
                    : message.code() + ": " + message.error();
        }
        return message.error();
    }
}
