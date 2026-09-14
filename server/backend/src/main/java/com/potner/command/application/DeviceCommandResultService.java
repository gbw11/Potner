package com.potner.command.application;

import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.CommandResultMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
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
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public DeviceCommandResultService(
            DeviceCommandRepository commandRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.commandRepository = commandRepository;
        this.eventPublisher = eventPublisher;
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
                reportedRunSeconds(command.getCommandType(), message),
                errorMessage(message),
                message.messageId().toString(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        );
        // 자동 케어 체인이 다음 단계를 잇는 근거다. 커밋 이후에 수신되므로 여기서 발행해도
        // 회신 반영이 실패하면 이벤트도 나가지 않는다.
        eventPublisher.publishEvent(new DeviceCommandCompletedEvent(
                command.getRequestId(),
                command.getPlantId(),
                command.getCommandType(),
                command.getInitiator(),
                command.getPurpose(),
                command.getDestination(),
                command.getStatus()
        ));
        return CommandResultApplyOutcome.APPLIED;
    }

    /**
     * 장치가 실제로 돌린 송풍 시간(초)이다.
     *
     * <p>라즈베리는 풍량·시간을 자기 설정으로 정하고 서버가 보낸 {@code seconds} 는 쓰지
     * 않는다. 회신의 {@code durationSec} 을 받아 두지 않으면 이력에는 서버가 요청한 값만
     * 남아 실제와 어긋난다.
     *
     * <p>초 단위로 반올림한다. 장치가 소수로 보내지만 컬럼이 정수이고, 팬 가동 시간을
     * 밀리초까지 남길 이유가 없다.
     *
     * <p><strong>송풍 회신에서만 받는다.</strong> 급수 회신에도 {@code durationSec} 이 실려
     * 오지만(펌프가 돈 시간) {@code run_seconds} 는 팬 전용 컬럼이고 API 응답도 그렇게
     * 약속되어 있다. 종류를 보지 않으면 급수 이력에 팬 가동 시간이 있는 것처럼 남는다.
     *
     * <p>급수는 시간을 남길 필요가 없다. 요청·실측을 {@code requested_ml}·{@code dispensed_ml}
     * 로 이미 나눠 갖고 있고, 사람이 보는 것은 나간 양이지 펌프가 돈 시간이 아니다.
     */
    private Integer reportedRunSeconds(DeviceCommandType commandType, CommandResultMessage message) {
        if (commandType != DeviceCommandType.FAN || message.durationSec() == null) {
            return null;
        }
        return message.durationSec().setScale(0, RoundingMode.HALF_UP).intValue();
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
