package com.potner.device.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.Robot;
import com.potner.device.config.DeviceProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 로봇 배터리 잔량을 갱신한다.
 *
 * <p>저장 위치가 {@code robot.battery_percent} 컬럼 하나다. 그래서 <strong>보고하는 장치가
 * 하나여야 한다.</strong> 라즈베리와 젯슨이 둘 다 보내면 서로 덮어쓰며 값이 흔들린다. 이동
 * 로봇은 배터리 팩 하나로 두 보드를 돌리므로 젯슨만 보내기로 정했고, 그 규칙을 서버가 강제한다.
 *
 * <p>어느 종류가 보고하는지를 프로퍼티로 둔 이유는 하드웨어 구성이 바뀔 수 있기 때문이다.
 * 배터리 센서가 라즈베리로 옮겨가면 이미지를 다시 만들지 않고 환경변수로 맞춘다.
 */
@Service
@Transactional
public class RobotBatteryService {

    private final IotDeviceRepository iotDeviceRepository;
    private final DeviceProperties properties;
    private final Clock clock;

    public RobotBatteryService(
            IotDeviceRepository iotDeviceRepository,
            DeviceProperties properties,
            Clock clock
    ) {
        this.iotDeviceRepository = iotDeviceRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 잔량을 기록한다.
     *
     * <p>장치 시계가 아니라 수신 시각을 저장한다. 시계가 틀어진 장치의 값을 그대로 쓰면 "언제
     * 것인지" 가 어긋나 오래된 값을 최신으로 보게 된다. 로봇 상태와 같은 방침이다.
     *
     * <p>잔량을 받았다는 사실 자체가 장치가 살아 있다는 증거라 heartbeat 도 함께 갱신한다.
     */
    public BatteryUpdateResult recordBattery(String deviceUid, int batteryPercent) {
        IotDevice device = iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull(deviceUid).orElse(null);
        if (device == null) {
            return BatteryUpdateResult.DEVICE_NOT_FOUND;
        }
        if (device.getDeviceType() != properties.batteryReporterType()) {
            return BatteryUpdateResult.UNEXPECTED_DEVICE_TYPE;
        }

        Robot robot = device.getRobot();
        if (robot == null) {
            return BatteryUpdateResult.ROBOT_NOT_FOUND;
        }

        LocalDateTime receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        device.recordHeartbeat(receivedAt);
        robot.recordBattery(batteryPercent, receivedAt);
        return BatteryUpdateResult.UPDATED;
    }
}
