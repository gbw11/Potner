package com.potner.location.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.Robot;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 물 부족 보고를 스테이션 상태에 반영한다.
 *
 * <p>보고의 귀속 경로는 센서 저장과 같다 — {@code iot_device.device_uid → robot → 위치}.
 * 스테이션 코드로 직접 찾지 않는 이유는, 보고하는 쪽(라즈베리)이 자기 device_uid 로 접속해
 * 있고 스테이션 코드는 모르기 때문이다. 코드는 사용자가 앱에 입력하는 값이다.
 *
 * <p>알림은 부족으로 <strong>바뀔 때 한 번만</strong> 낸다. 장치가 같은 상태를 주기적으로 반복
 * 보고하므로 매번 알리면 물을 채울 때까지 알림이 쏟아진다. 보충(false 보고)으로 해제되면
 * 다음 부족 때 다시 나간다 — 이상 알림의 활성/해제와 같은 사고방식이다.
 */
@Service
public class StationWaterLowService {

    private final IotDeviceRepository iotDeviceRepository;
    private final RobotLocationRepository locationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StationWaterLowService(
            IotDeviceRepository iotDeviceRepository,
            RobotLocationRepository locationRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.iotDeviceRepository = iotDeviceRepository;
        this.locationRepository = locationRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public WaterLowUpdateResult report(String deviceUid, boolean waterLow, LocalDateTime reportedAt) {
        IotDevice device = iotDeviceRepository.findByDeviceUid(deviceUid).orElse(null);
        if (device == null) {
            return WaterLowUpdateResult.DEVICE_NOT_FOUND;
        }

        Robot robot = device.getRobot();
        RobotLocation station = locationRepository
                .findByRobotIdAndLocationType(robot.getId(), RobotLocationType.WATER_STATION)
                .orElse(null);
        if (station == null) {
            // 장치는 붙었는데 스테이션 등록(앱의 코드 입력)이 아직이다. 보고를 버리되 로그로
            // 드러나야 "알림이 왜 안 오지" 의 원인을 찾을 수 있다.
            return WaterLowUpdateResult.STATION_NOT_FOUND;
        }

        boolean wasLow = station.isWaterLow();
        boolean becameLow = station.reportWaterLow(waterLow, reportedAt);

        if (becameLow) {
            eventPublisher.publishEvent(new StationWaterLowEvent(
                    robot.getUser().getId(),
                    robot.getId(),
                    station.getStationCode()
            ));
            return WaterLowUpdateResult.BECAME_LOW;
        }
        if (waterLow) {
            return WaterLowUpdateResult.STILL_LOW;
        }
        return wasLow ? WaterLowUpdateResult.CLEARED : WaterLowUpdateResult.ALREADY_CLEAR;
    }
}
