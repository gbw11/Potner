package com.potner.arrival.application;

import com.potner.arrival.config.ArrivalProperties;
import com.potner.arrival.domain.ArrivalEvent;
import com.potner.arrival.domain.ArrivalEventRepository;
import com.potner.arrival.domain.ArrivalEventSource;
import com.potner.arrival.domain.ArrivalEventType;
import com.potner.arrival.dto.ArrivalEventRequest;
import com.potner.arrival.dto.ArrivalEventResponse;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.user.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArrivalServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T06:00:00Z");
    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String JETSON_UID = "jetson-01";
    private static final UUID EVENT_ID =
            UUID.fromString("20000000-0000-0000-0000-0000000000bb");
    private static final UUID VISIT_ID =
            UUID.fromString("30000000-0000-0000-0000-0000000000cc");

    @Mock
    private ArrivalEventRepository arrivalEventRepository;
    @Mock
    private RobotRepository robotRepository;
    @Mock
    private IotDeviceRepository iotDeviceRepository;
    @Mock
    private RobotLocationRepository locationRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ArrivalService service;
    private Robot robot;

    @BeforeEach
    void setUp() {
        service = new ArrivalService(
                arrivalEventRepository,
                robotRepository,
                iotDeviceRepository,
                locationRepository,
                new ArrivalProperties(120, 300, 120, 30, 600, 300),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        robot = Robot.register(mock(AppUser.class), "robot-shell-01", "거실 로봇");
        IotDevice jetson = IotDevice.register(robot, JETSON_UID, IotDeviceType.JETSON_ORIN);
        lenient().when(
                        robotRepository
                                .findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(USER_ID))
                .thenReturn(List.of(robot));
        lenient().when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(robot.getId()))
                .thenReturn(List.of(jetson));
        lenient().when(arrivalEventRepository.save(any(ArrivalEvent.class)))
                .thenAnswer(call -> call.getArgument(0));
        givenLocation(RobotLocationType.HOME, "0.100", "0.200", "0.3000");
    }

    @Test
    void approachPublishesGreetingAndHomePosesWithConfiguredTimes() {
        givenLocation(RobotLocationType.GREETING, "1.100", "1.200", "1.3000");

        ArrivalEventResponse response = service.process(USER_ID, approach());

        assertThat(response.status()).isEqualTo("COMMAND_PUBLISHED");
        ArgumentCaptor<ArrivalCommandIssuedEvent> event =
                ArgumentCaptor.forClass(ArrivalCommandIssuedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().eventId()).isEqualTo(EVENT_ID.toString());
        assertThat(event.getValue().deviceUid()).isEqualTo(JETSON_UID);
        assertThat(event.getValue().eventType()).isEqualTo(ArrivalEventType.APPROACH);
        assertThat(event.getValue().greetingX()).isEqualByComparingTo("1.100");
        assertThat(event.getValue().homeX()).isEqualByComparingTo("0.100");
        assertThat(event.getValue().welcomeWaitSeconds()).isEqualTo(120);
        assertThat(event.getValue().totalTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void repeatedEventIdIsIgnoredBeforeAnotherCommandIsPublished() {
        when(arrivalEventRepository.existsById(EVENT_ID.toString())).thenReturn(true);

        ArrivalEventResponse response = service.process(USER_ID, approach());

        assertThat(response.status()).isEqualTo("DUPLICATE_IGNORED");
        verify(robotRepository, never())
                .findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void eventOlderThanConfiguredMaximumIsRejectedBeforeRobotLookup() {
        ArrivalEventRequest stale = approachAt(
                OffsetDateTime.ofInstant(NOW.minusSeconds(601), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.process(USER_ID, stale))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.STALE_EVENT);
        verify(robotRepository, never())
                .findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void eventTooFarInTheFutureIsRejectedBeforeRobotLookup() {
        ArrivalEventRequest future = approachAt(
                OffsetDateTime.ofInstant(NOW.plusSeconds(301), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.process(USER_ID, future))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_EVENT_TIME);
        verify(robotRepository, never())
                .findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void duplicateEventRemainsIdempotentEvenAfterItsTimestampGetsOld() {
        when(arrivalEventRepository.existsById(EVENT_ID.toString())).thenReturn(true);

        ArrivalEventResponse response = service.process(
                USER_ID,
                approachAt(OffsetDateTime.ofInstant(NOW.minusSeconds(601), ZoneOffset.UTC)));

        assertThat(response.status()).isEqualTo("DUPLICATE_IGNORED");
        verify(robotRepository, never())
                .findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(any());
    }

    @Test
    void cancelUsesTheSameVisitAndReturnsHome() {
        when(arrivalEventRepository.existsByVisitIdAndUserIdAndEventType(
                VISIT_ID.toString(), USER_ID, ArrivalEventType.APPROACH))
                .thenReturn(true);

        ArrivalEventResponse response = service.process(USER_ID, cancel());

        assertThat(response.status()).isEqualTo("COMMAND_PUBLISHED");
        ArgumentCaptor<ArrivalCommandIssuedEvent> event =
                ArgumentCaptor.forClass(ArrivalCommandIssuedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().visitId()).isEqualTo(VISIT_ID.toString());
        assertThat(event.getValue().eventType()).isEqualTo(ArrivalEventType.CANCEL);
        assertThat(event.getValue().greetingX()).isNull();
        assertThat(event.getValue().homeY()).isEqualByComparingTo("0.200");
        verify(locationRepository, never()).findByRobotIdAndLocationType(
                robot.getId(), RobotLocationType.GREETING);
    }

    @Test
    void cancelWithoutAnApproachForTheVisitIsRejected() {
        when(arrivalEventRepository.existsByVisitIdAndUserIdAndEventType(
                VISIT_ID.toString(), USER_ID, ArrivalEventType.APPROACH))
                .thenReturn(false);

        assertThatThrownBy(() -> service.process(USER_ID, cancel()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ARRIVAL_VISIT_NOT_FOUND);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void multipleRobotsAreRejectedInsteadOfPickingOneArbitrarily() {
        Robot another = Robot.register(mock(AppUser.class), "robot-shell-02", "다른 로봇");
        when(robotRepository.findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(USER_ID))
                .thenReturn(List.of(robot, another));

        assertThatThrownBy(() -> service.process(USER_ID, approach()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ARRIVAL_MULTIPLE_ROBOTS);
    }

    @Test
    void releasedJetsonIsNotSelectedForArrivalCommands() {
        IotDevice released =
                IotDevice.register(robot, "old-jetson", IotDeviceType.JETSON_ORIN);
        released.release(NOW.atZone(ZoneOffset.UTC).toLocalDateTime());
        when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(robot.getId()))
                .thenReturn(List.of(released));

        assertThatThrownBy(() -> service.process(USER_ID, approach()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DEVICE_NOT_FOUND);
    }

    private void givenLocation(
            RobotLocationType type,
            String x,
            String y,
            String yaw
    ) {
        RobotLocation location = RobotLocation.register(robot.getId(), type, null);
        location.updatePose(new BigDecimal(x), new BigDecimal(y), new BigDecimal(yaw));
        lenient().when(locationRepository.findByRobotIdAndLocationType(robot.getId(), type))
                .thenReturn(Optional.of(location));
    }

    private ArrivalEventRequest approach() {
        return approachAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private ArrivalEventRequest approachAt(OffsetDateTime occurredAt) {
        return new ArrivalEventRequest(
                EVENT_ID,
                VISIT_ID,
                ArrivalEventType.APPROACH,
                ArrivalEventSource.DEBUG_BUTTON,
                "DEBUG_BUTTON",
                occurredAt
        );
    }

    private ArrivalEventRequest cancel() {
        return new ArrivalEventRequest(
                EVENT_ID,
                VISIT_ID,
                ArrivalEventType.CANCEL,
                ArrivalEventSource.DEBUG_BUTTON,
                "DEBUG_BUTTON",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }
}
