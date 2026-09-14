package com.potner.device.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 로봇의 현재 행동 상태를 갱신한다.
 *
 * <p>상태를 보내는 것은 젯슨이지만 저장 위치는 {@code robot} 이다. 이동하고 급수를 받는 주체가
 * 로봇이고, 로봇 하나에 젯슨이 하나다.
 */
@Service
@Transactional
public class RobotStateService {

    private final IotDeviceRepository iotDeviceRepository;
    private final Clock clock;

    public RobotStateService(IotDeviceRepository iotDeviceRepository, Clock clock) {
        this.iotDeviceRepository = iotDeviceRepository;
        this.clock = clock;
    }

    /**
     * 상태를 기록한다.
     *
     * <p>{@code changedAt} 을 쓰지 않고 수신 시각을 쓴다. 장치 시계가 틀어져 있으면 상태 전이
     * 시각이 뒤엉키고, 급수 작업이 그 값으로 타임아웃을 재면 영원히 기다리거나 즉시 실패한다.
     * 서버 시계 하나만 신뢰하는 것이 {@code sensor_reading.received_at} 과 같은 방침이다.
     *
     * <p>상태를 받았다는 사실 자체가 장치가 살아 있다는 증거라 heartbeat 도 함께 갱신한다.
     * 하드웨어가 상태 보고로 heartbeat 를 대체하더라도 장치가 OFFLINE 으로 오판되지 않는다.
     */
    public RobotStateUpdateResult recordState(String deviceUid, RobotState state) {
        IotDevice device = iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull(deviceUid).orElse(null);
        if (device == null) {
            return RobotStateUpdateResult.DEVICE_NOT_FOUND;
        }

        Robot robot = device.getRobot();
        if (robot == null) {
            return RobotStateUpdateResult.ROBOT_NOT_FOUND;
        }

        LocalDateTime receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        device.recordHeartbeat(receivedAt);

        return robot.changeState(state, receivedAt)
                ? RobotStateUpdateResult.CHANGED
                : RobotStateUpdateResult.UNCHANGED;
    }
}
