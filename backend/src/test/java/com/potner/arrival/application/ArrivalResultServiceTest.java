package com.potner.arrival.application;

import com.potner.arrival.domain.ArrivalEvent;
import com.potner.arrival.domain.ArrivalEventRepository;
import com.potner.arrival.domain.ArrivalEventSource;
import com.potner.arrival.domain.ArrivalEventType;
import com.potner.arrival.domain.ArrivalProcessingStatus;
import com.potner.command.application.CommandResultApplyOutcome;
import com.potner.command.dto.CommandResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArrivalResultServiceTest {

    private static final String EVENT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String MESSAGE_ID = "40000000-0000-0000-0000-0000000000dd";
    private static final Instant NOW = Instant.parse("2026-07-31T06:00:00Z");

    @Mock
    private ArrivalEventRepository repository;

    private ArrivalResultService service;
    private ArrivalEvent event;

    @BeforeEach
    void setUp() {
        service = new ArrivalResultService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        event = ArrivalEvent.received(
                EVENT_ID,
                "30000000-0000-0000-0000-0000000000cc",
                "user-01",
                "robot-01",
                "jetson-01",
                ArrivalEventType.APPROACH,
                ArrivalEventSource.DEBUG_BUTTON,
                "DEBUG_BUTTON",
                LocalDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC)
        );
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(event));
    }

    @Test
    void greetingArrivalOkCompletesTheApproachEvent() {
        CommandResultApplyOutcome outcome = service.apply(
                "jetson-01",
                ArrivalEventType.APPROACH,
                result("OK")
        );

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(event.getProcessingStatus()).isEqualTo(ArrivalProcessingStatus.OK);
        assertThat(event.getReportedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void resultFromAnotherDeviceIsRejected() {
        CommandResultApplyOutcome outcome = service.apply(
                "other-jetson",
                ArrivalEventType.APPROACH,
                result("OK")
        );

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.DEVICE_MISMATCH);
        assertThat(event.getProcessingStatus())
                .isEqualTo(ArrivalProcessingStatus.COMMAND_PUBLISHED);
    }

    @Test
    void latePhysicalResultOverwritesTimeout() {
        event.applyResult(
                ArrivalProcessingStatus.TIMED_OUT,
                null,
                "timeout-marker",
                LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC)
        );

        CommandResultApplyOutcome outcome = service.apply(
                "jetson-01",
                ArrivalEventType.APPROACH,
                result("OK")
        );

        assertThat(outcome).isEqualTo(CommandResultApplyOutcome.APPLIED);
        assertThat(event.getProcessingStatus()).isEqualTo(ArrivalProcessingStatus.OK);
    }

    private CommandResultMessage result(String status) {
        return new CommandResultMessage(
                UUID.fromString(MESSAGE_ID),
                "jetson-01",
                EVENT_ID,
                status,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
