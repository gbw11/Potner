package com.potner.device.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotState;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RobotStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T05:30:00Z");
    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    private RobotStateService robotStateService;

    @BeforeEach
    void setUp() {
        robotStateService = new RobotStateService(
                iotDeviceRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void changedStateIsStoredWithTheServerClock() {
        // 장치 시계를 쓰지 않는다. 틀어져 있으면 급수 작업의 타임아웃 계산이 그만큼 틀어진다.
        Robot robot = mock(Robot.class);
        IotDevice device = device(robot);
        when(robot.changeState(RobotState.NAVIGATING, RECEIVED_AT)).thenReturn(true);

        assertThat(robotStateService.recordState("jetson-01", RobotState.NAVIGATING))
                .isEqualTo(RobotStateUpdateResult.CHANGED);
        verify(device).recordHeartbeat(RECEIVED_AT);
    }

    @Test
    void sameStateIsReportedAsUnchanged() {
        Robot robot = mock(Robot.class);
        IotDevice device = device(robot);
        when(robot.changeState(any(), any())).thenReturn(false);

        assertThat(robotStateService.recordState("jetson-01", RobotState.NAVIGATING))
                .isEqualTo(RobotStateUpdateResult.UNCHANGED);
        // 상태가 그대로여도 장치는 살아 있다. heartbeat 는 갱신해야 OFFLINE 으로 오판되지 않는다.
        verify(device).recordHeartbeat(RECEIVED_AT);
    }

    @Test
    void unknownDeviceIsReportedWithoutTouchingAnything() {
        when(iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull("jetson-99")).thenReturn(Optional.empty());

        assertThat(robotStateService.recordState("jetson-99", RobotState.IDLE))
                .isEqualTo(RobotStateUpdateResult.DEVICE_NOT_FOUND);
    }

    @Test
    void deviceWithoutARobotIsReportedSeparately() {
        // 데이터가 깨진 경우다. DEVICE_NOT_FOUND 와 섞으면 로그만 보고 원인을 가릴 수 없다.
        IotDevice device = device(null);

        assertThat(robotStateService.recordState("jetson-01", RobotState.IDLE))
                .isEqualTo(RobotStateUpdateResult.ROBOT_NOT_FOUND);
        verify(device, never()).recordHeartbeat(any());
    }

    private IotDevice device(Robot robot) {
        IotDevice device = mock(IotDevice.class);
        org.mockito.Mockito.lenient().when(device.getRobot()).thenReturn(robot);
        when(iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull("jetson-01")).thenReturn(Optional.of(device));
        return device;
    }
}
