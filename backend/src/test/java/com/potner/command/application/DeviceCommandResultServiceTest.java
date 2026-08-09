package com.potner.command.application;

import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.CommandResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCommandResultServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T06:30:00Z");
    private static final String PI_UID = "raspberry-01";
    private static final UUID MESSAGE_ID = UUID.fromString("40000000-0000-0000-0000-0000000000dd");

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DeviceCommandResultService service;
    private DeviceCommand command;
    private String requestId;

    @BeforeEach
    void setUp() {
        service = new DeviceCommandResultService(
                commandRepository,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        command = DeviceCommand.issue(
                "plant-1", "robot-1", PI_UID,
                DeviceCommandType.WATER,
                CommandInitiator.USER,
                CommandPurpose.WATERING,
                new BigDecimal("350.00"),
                null,
                null,
                LocalDateTime.ofInstant(NOW.minusSeconds(20), ZoneOffset.UTC)
        );
        requestId = command.getRequestId();
        // rejectsAnUnknownRequestId 는 다른 requestId 를 조회하므로 이 스텁을 쓰지 않는다.
        org.mockito.Mockito.lenient()
                .when(commandRepository.findById(requestId)).thenReturn(Optional.of(command));
    }

    @Test
    void appliesASuccessfulWaterResult() {
        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.WATER,
                result("OK", new BigDecimal("348.50"), null, null));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.OK);
        assertThat(command.getDispensedMl()).isEqualByComparingTo("348.50");
        assertThat(command.getReportedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(command.getResultMessageId()).isEqualTo(MESSAGE_ID.toString());
    }

    @Test
    void publishesACompletedEventOnlyWhenTheResultIsApplied() {
        // 자동 케어 체인이 다음 단계를 잇는 근거다. 명령 속성이 그대로 실려야 체인이 단계를
        // 구분할 수 있다.
        service.apply(PI_UID, DeviceCommandType.WATER,
                result("OK", new BigDecimal("348.50"), null, null));

        org.mockito.ArgumentCaptor<DeviceCommandCompletedEvent> event =
                org.mockito.ArgumentCaptor.forClass(DeviceCommandCompletedEvent.class);
        org.mockito.Mockito.verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().requestId()).isEqualTo(requestId);
        assertThat(event.getValue().commandType()).isEqualTo(DeviceCommandType.WATER);
        assertThat(event.getValue().initiator()).isEqualTo(CommandInitiator.USER);
        assertThat(event.getValue().status()).isEqualTo(DeviceCommandStatus.OK);

        // 반영되지 않은 회신(중복 등)은 이벤트가 나가면 안 된다 — 체인이 두 번 이어진다.
        service.apply(PI_UID, DeviceCommandType.WATER,
                result("OK", new BigDecimal("348.50"), null, null));
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.times(1))
                .publishEvent(any(DeviceCommandCompletedEvent.class));
    }

    @Test
    void appliesBusyAsItsOwnStatusNotAsAFailure() {
        // BUSY 는 장치가 앞선 작업 중이라 거절한 것이다. 실패와 구분해야 재시도 판단이 다르다.
        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.WATER, result("BUSY", null, null, null));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.BUSY);
    }

    @Test
    void keepsTheCaptureErrorCodeInTheMessage() {
        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.WATER,
                result("ERROR", null, "camera unavailable", "CAPTURE_FAILED"));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(command.getErrorMessage()).isEqualTo("CAPTURE_FAILED: camera unavailable");
    }

    @Test
    void ignoresADuplicateRedelivery() {
        // QoS 1 재전송이다. 같은 messageId 는 상태 검사보다 먼저 걸러야 가짜 신호가 안 남는다.
        service.apply(PI_UID, DeviceCommandType.WATER,
                result("OK", new BigDecimal("348.50"), null, null));

        CommandResultApplyOutcome second = service.apply(
                PI_UID, DeviceCommandType.WATER,
                result("OK", new BigDecimal("348.50"), null, null));

        assertThat(second).isEqualTo(CommandResultApplyOutcome.DUPLICATE);
    }

    @Test
    void rejectsAResultFromADifferentDevice() {
        // ACL 은 토픽만 지킨다. 다른 장치가 남의 requestId 를 실어 보내면 여기서 걸린다.
        CommandResultApplyOutcome outcome = service.apply(
                "jetson-01", DeviceCommandType.WATER, result("OK", null, null, null));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.DEVICE_MISMATCH);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.ISSUED);
    }

    @Test
    void rejectsAnUnknownRequestId() {
        when(commandRepository.findById("no-such-request")).thenReturn(Optional.empty());

        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.WATER,
                new CommandResultMessage(MESSAGE_ID, PI_UID, "no-such-request",
                        "OK", null, null, null, null, null, null));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.UNKNOWN_REQUEST);
    }

    @Test
    void overwritesATimedOutCommandWithTheLateResult() {
        // 타임아웃은 서버의 추정이고 회신은 물리적 사실이다. 실제 급수량은 일기에 실리므로
        // 늦은 회신을 버리면 기록이 틀어진다.
        command.applyResult(DeviceCommandStatus.TIMED_OUT, null, null, null, null, null);

        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.WATER,
                result("OK", new BigDecimal("120.00"), null, null));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.OK);
        assertThat(command.getDispensedMl()).isEqualByComparingTo("120.00");
    }

    @Test
    void recordsTheRunSecondsTheDeviceActuallyUsed() {
        // 라즈베리는 풍량·시간을 자기 설정으로 정하고 서버가 보낸 seconds 를 쓰지 않는다.
        // 회신값을 받아 두지 않으면 이력에 요청값(30)만 남아 실제(10)와 어긋난다.
        DeviceCommand fan = fanCommand(30);

        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.FAN, fanResult(fan, new BigDecimal("10.4")));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(fan.getRunSeconds()).isEqualTo(10);
    }

    @Test
    void keepsTheRequestedRunSecondsWhenTheDeviceReportsNone() {
        // 없다고 지우면 팬 이력이 비어 버린다. 값이 있을 때만 덮어쓴다.
        DeviceCommand fan = fanCommand(30);

        service.apply(PI_UID, DeviceCommandType.FAN, fanResult(fan, null));

        assertThat(fan.getRunSeconds()).isEqualTo(30);
    }

    @Test
    void ignoresTheDurationOnAWaterResult() {
        // 급수 회신에도 durationSec 이 실려 온다(펌프가 돈 시간). 하지만 run_seconds 는 팬 전용
        // 컬럼이고 API 응답도 그렇게 약속되어 있어, 종류를 보지 않으면 급수 이력에 팬 가동
        // 시간이 있는 것처럼 남는다. 급수의 실측은 dispensedMl 이다.
        CommandResultMessage message = new CommandResultMessage(
                MESSAGE_ID, PI_UID, requestId, "OK",
                new BigDecimal("350.00"), new BigDecimal("348.50"),
                new BigDecimal("13.9"), null, null, null);

        service.apply(PI_UID, DeviceCommandType.WATER, message);

        assertThat(command.getRunSeconds()).isNull();
        assertThat(command.getDispensedMl()).isEqualByComparingTo("348.50");
    }

    @Test
    void appliesSkippedAsItsOwnStatusNotAsAFailure() {
        // 과급수 가드가 막은 것이다. 이 값을 모르면 회신이 통째로 버려져 명령이 ISSUED 로 남고,
        // 급수 회차가 스테이션에서 멈춘 채 로봇이 타임아웃까지 거기 서 있게 된다.
        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.WATER,
                result("SKIPPED", BigDecimal.ZERO, null, null));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.SKIPPED);
        assertThat(command.getDispensedMl()).isEqualByComparingTo("0");
    }

    @Test
    void publishesACompletedEventForSkippedSoTheChainCanContinue() {
        // 물을 주지 않았을 뿐 로봇은 스테이션에 서 있다. 이벤트가 나가야 송풍과 복귀가 이어진다.
        service.apply(PI_UID, DeviceCommandType.WATER,
                result("SKIPPED", BigDecimal.ZERO, null, null));

        org.mockito.ArgumentCaptor<DeviceCommandCompletedEvent> event =
                org.mockito.ArgumentCaptor.forClass(DeviceCommandCompletedEvent.class);
        org.mockito.Mockito.verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().status()).isEqualTo(DeviceCommandStatus.SKIPPED);
        assertThat(event.getValue().status().continuesChain()).isTrue();
    }

    @Test
    void rejectsAnUnknownStatusWord() {
        // 장치가 어휘를 늘리면 조용히 넘기지 않고 걸러 로그로 드러낸다.
        CommandResultApplyOutcome outcome = service.apply(
                PI_UID, DeviceCommandType.WATER, result("PARTIAL", null, null, null));

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.INVALID_STATUS);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.ISSUED);
    }

    /** 서버가 {@code seconds} 를 요청해 발행한 송풍 명령이다. */
    private DeviceCommand fanCommand(int requestedSeconds) {
        DeviceCommand fan = DeviceCommand.issue(
                "plant-1", "robot-1", PI_UID,
                DeviceCommandType.FAN,
                CommandInitiator.AUTO,
                CommandPurpose.DRYING,
                null,
                null,
                requestedSeconds,
                LocalDateTime.ofInstant(NOW.minusSeconds(20), ZoneOffset.UTC)
        );
        when(commandRepository.findById(fan.getRequestId())).thenReturn(Optional.of(fan));
        return fan;
    }

    private CommandResultMessage fanResult(DeviceCommand fan, BigDecimal durationSec) {
        return new CommandResultMessage(
                MESSAGE_ID, PI_UID, fan.getRequestId(), "OK",
                null, null, durationSec, null, null, null);
    }

    private CommandResultMessage result(
            String status,
            BigDecimal dispensedMl,
            String error,
            String code
    ) {
        return new CommandResultMessage(
                MESSAGE_ID,
                PI_UID,
                requestId,
                status,
                new BigDecimal("350.00"),
                dispensedMl,
                null,
                null,
                error,
                code
        );
    }
}
