package com.potner.location.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.Robot;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationWaterLowServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final String PI_UID = "raspberry-01";
    private static final LocalDateTime REPORTED_AT = LocalDateTime.of(2026, 7, 30, 7, 0);

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    @Mock
    private RobotLocationRepository locationRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private StationWaterLowService service;
    private RobotLocation station;

    @BeforeEach
    void setUp() {
        service = new StationWaterLowService(
                iotDeviceRepository,
                locationRepository,
                eventPublisher
        );
        station = RobotLocation.register(ROBOT_ID, RobotLocationType.WATER_STATION, "WS-1234");
        givenDeviceWithRobot();
        lenient().when(locationRepository
                        .findByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.WATER_STATION))
                .thenReturn(Optional.of(station));
    }

    @Test
    void notifiesOnceWhenTheStationBecomesLow() {
        WaterLowUpdateResult result = service.report(PI_UID, true, REPORTED_AT);

        assertThat(result).isEqualTo(WaterLowUpdateResult.BECAME_LOW);
        assertThat(station.isWaterLow()).isTrue();
        assertThat(station.getWaterLowAt()).isEqualTo(REPORTED_AT);

        ArgumentCaptor<StationWaterLowEvent> event =
                ArgumentCaptor.forClass(StationWaterLowEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(USER_ID);
        assertThat(event.getValue().stationCode()).isEqualTo("WS-1234");
    }

    @Test
    void repeatedLowReportsDoNotNotifyAgain() {
        // 장치는 부족한 동안 같은 상태를 주기적으로 반복 보고한다. 매번 알리면 물을 채울
        // 때까지 알림이 쏟아진다.
        service.report(PI_UID, true, REPORTED_AT);
        WaterLowUpdateResult second = service.report(PI_UID, true, REPORTED_AT.plusMinutes(5));

        assertThat(second).isEqualTo(WaterLowUpdateResult.STILL_LOW);
        // 보고 시각은 계속 갱신된다. "마지막으로 부족이 보고된 시각" 이다.
        assertThat(station.getWaterLowAt()).isEqualTo(REPORTED_AT.plusMinutes(5));
        verify(eventPublisher, times(1)).publishEvent(any(StationWaterLowEvent.class));
    }

    @Test
    void refillClearsTheFlagAndTheNextShortageNotifiesAgain() {
        service.report(PI_UID, true, REPORTED_AT);
        WaterLowUpdateResult cleared = service.report(PI_UID, false, REPORTED_AT.plusHours(1));
        WaterLowUpdateResult lowAgain = service.report(PI_UID, true, REPORTED_AT.plusHours(2));

        assertThat(cleared).isEqualTo(WaterLowUpdateResult.CLEARED);
        assertThat(lowAgain).isEqualTo(WaterLowUpdateResult.BECAME_LOW);
        verify(eventPublisher, times(2)).publishEvent(any(StationWaterLowEvent.class));
    }

    @Test
    void aClearReportWhileAlreadyClearChangesNothing() {
        WaterLowUpdateResult result = service.report(PI_UID, false, REPORTED_AT);

        assertThat(result).isEqualTo(WaterLowUpdateResult.ALREADY_CLEAR);
        verify(eventPublisher, never()).publishEvent(any(StationWaterLowEvent.class));
    }

    @Test
    void ignoresAReportFromAnUnknownDevice() {
        when(iotDeviceRepository.findByDeviceUid("unknown")).thenReturn(Optional.empty());

        assertThat(service.report("unknown", true, REPORTED_AT))
                .isEqualTo(WaterLowUpdateResult.DEVICE_NOT_FOUND);
    }

    @Test
    void ignoresAReportWhenNoWaterStationIsRegistered() {
        // 장치는 붙었는데 앱의 스테이션 코드 입력이 아직이다. 알림이 왜 안 오는지 이 결과가
        // 로그로 드러나야 한다.
        when(locationRepository
                .findByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.WATER_STATION))
                .thenReturn(Optional.empty());

        assertThat(service.report(PI_UID, true, REPORTED_AT))
                .isEqualTo(WaterLowUpdateResult.STATION_NOT_FOUND);
        verify(eventPublisher, never()).publishEvent(any(StationWaterLowEvent.class));
    }

    private void givenDeviceWithRobot() {
        AppUser user = mock(AppUser.class);
        lenient().when(user.getId()).thenReturn(USER_ID);
        Robot robot = mock(Robot.class);
        lenient().when(robot.getId()).thenReturn(ROBOT_ID);
        lenient().when(robot.getUser()).thenReturn(user);
        IotDevice device = mock(IotDevice.class);
        lenient().when(device.getRobot()).thenReturn(robot);
        lenient().when(iotDeviceRepository.findByDeviceUid(PI_UID)).thenReturn(Optional.of(device));
    }
}
