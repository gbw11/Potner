package com.potner.device.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.Robot;
import com.potner.device.config.DeviceProperties;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RobotBatteryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    private RobotBatteryService robotBatteryService;

    @BeforeEach
    void setUp() {
        robotBatteryService = new RobotBatteryService(
                iotDeviceRepository,
                new DeviceProperties(IotDeviceType.JETSON_ORIN),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void jetsonReportIsStoredWithTheServerClock() {
        // 장치 시계를 쓰지 않는다. 틀어져 있으면 '언제 것인지' 가 어긋나 오래된 값을 최신으로 본다.
        Robot robot = mock(Robot.class);
        IotDevice device = device(IotDeviceType.JETSON_ORIN, robot);

        assertThat(robotBatteryService.recordBattery("jetson-01", 78))
                .isEqualTo(BatteryUpdateResult.UPDATED);
        verify(robot).recordBattery(78, RECEIVED_AT);
        // 잔량을 보냈다는 사실 자체가 살아 있다는 증거다.
        verify(device).recordHeartbeat(RECEIVED_AT);
    }

    @Test
    void raspberryReportIsRejectedBecauseTheColumnIsShared() {
        // robot.battery_percent 가 컬럼 하나라 두 장치가 보내면 서로 덮어써 값이 흔들린다.
        Robot robot = mock(Robot.class);
        device(IotDeviceType.RASPBERRY_PI, robot);

        assertThat(robotBatteryService.recordBattery("jetson-01", 78))
                .isEqualTo(BatteryUpdateResult.UNEXPECTED_DEVICE_TYPE);
        verify(robot, never()).recordBattery(anyInt(), any());
    }

    @Test
    void reporterDeviceTypeCanMoveToTheRaspberryWithoutCodeChanges() {
        // 배터리 센서가 라즈베리로 옮겨가면 이미지를 다시 만들지 않고 환경변수만 바꾼다.
        RobotBatteryService raspberryReporter = new RobotBatteryService(
                iotDeviceRepository,
                new DeviceProperties(IotDeviceType.RASPBERRY_PI),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Robot robot = mock(Robot.class);
        device(IotDeviceType.RASPBERRY_PI, robot);

        assertThat(raspberryReporter.recordBattery("jetson-01", 42))
                .isEqualTo(BatteryUpdateResult.UPDATED);
        verify(robot).recordBattery(42, RECEIVED_AT);
    }

    @Test
    void unknownDeviceIsReported() {
        when(iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull("jetson-99")).thenReturn(Optional.empty());

        assertThat(robotBatteryService.recordBattery("jetson-99", 78))
                .isEqualTo(BatteryUpdateResult.DEVICE_NOT_FOUND);
    }

    @Test
    void deviceWithoutARobotHasNowhereToStoreTheValue() {
        IotDevice device = device(IotDeviceType.JETSON_ORIN, null);

        assertThat(robotBatteryService.recordBattery("jetson-01", 78))
                .isEqualTo(BatteryUpdateResult.ROBOT_NOT_FOUND);
        verify(device, never()).recordHeartbeat(any());
    }

    private IotDevice device(IotDeviceType deviceType, Robot robot) {
        IotDevice device = mock(IotDevice.class);
        org.mockito.Mockito.lenient().when(device.getDeviceType()).thenReturn(deviceType);
        org.mockito.Mockito.lenient().when(device.getRobot()).thenReturn(robot);
        when(iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull("jetson-01")).thenReturn(Optional.of(device));
        return device;
    }
}
