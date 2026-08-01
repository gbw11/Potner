package com.potner.arrival.application;

import com.potner.arrival.domain.ArrivalEventType;
import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ArrivalCommandPublishListenerTest {

    @Mock
    private RobotCommandPublisher commandPublisher;

    @Test
    void approachBecomesWelcomeStartAndUsesEventIdAsRequestId() {
        ArrivalCommandPublishListener listener =
                new ArrivalCommandPublishListener(commandPublisher);

        listener.onCommandIssued(event(ArrivalEventType.APPROACH));

        ArgumentCaptor<RobotCommand> command = ArgumentCaptor.forClass(RobotCommand.class);
        verify(commandPublisher).publish(
                org.mockito.ArgumentMatchers.eq("jetson-01"),
                command.capture()
        );
        assertThat(command.getValue().name()).isEqualTo("welcome_start");
        WelcomeStartPayload payload = (WelcomeStartPayload) command.getValue().payload();
        assertThat(payload.requestId()).isEqualTo("event-01");
        assertThat(payload.destination()).isEqualTo("GREETING");
        assertThat(payload.returnDestination()).isEqualTo("HOME");
        assertThat(payload.waitSeconds()).isEqualTo(120);
    }

    @Test
    void cancelBecomesWelcomeCancelReturningHome() {
        ArrivalCommandPublishListener listener =
                new ArrivalCommandPublishListener(commandPublisher);

        listener.onCommandIssued(event(ArrivalEventType.CANCEL));

        ArgumentCaptor<RobotCommand> command = ArgumentCaptor.forClass(RobotCommand.class);
        verify(commandPublisher).publish(
                org.mockito.ArgumentMatchers.eq("jetson-01"),
                command.capture()
        );
        assertThat(command.getValue().name()).isEqualTo("welcome_cancel");
        WelcomeCancelPayload payload = (WelcomeCancelPayload) command.getValue().payload();
        assertThat(payload.requestId()).isEqualTo("event-01");
        assertThat(payload.returnDestination()).isEqualTo("HOME");
    }

    private ArrivalCommandIssuedEvent event(ArrivalEventType type) {
        return new ArrivalCommandIssuedEvent(
                "event-01",
                "visit-01",
                "jetson-01",
                type,
                new BigDecimal("1.100"),
                new BigDecimal("1.200"),
                new BigDecimal("1.3000"),
                new BigDecimal("0.100"),
                new BigDecimal("0.200"),
                new BigDecimal("0.3000"),
                120,
                300,
                OffsetDateTime.parse("2026-07-31T06:00:00Z")
        );
    }
}
