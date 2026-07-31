package com.potner.location.application;

import com.potner.alert.application.AlertOpenedEvent;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
 *
 * <p>로봇에 식물이 배정되어 있으면 {@code alert} 에도 기록한다. 푸시만 보내면 그 순간을 놓친
 * 사용자가 물 부족을 알 방법이 없다. 기록·발송 모두 알림 경로({@link AlertOpenedEvent})가
 * 담당하고, 배정 전에는 알림을 매달 식물이 없으므로({@code alert.plant_id} NOT NULL) 기존
 * 폴백({@link StationWaterLowEvent} → 푸시만)이 대신한다.
 */
@Service
public class StationWaterLowService {

    private final IotDeviceRepository iotDeviceRepository;
    private final RobotLocationRepository locationRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final PlantRepository plantRepository;
    private final AlertRepository alertRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public StationWaterLowService(
            IotDeviceRepository iotDeviceRepository,
            RobotLocationRepository locationRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            PlantRepository plantRepository,
            AlertRepository alertRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.iotDeviceRepository = iotDeviceRepository;
        this.locationRepository = locationRepository;
        this.assignmentRepository = assignmentRepository;
        this.plantRepository = plantRepository;
        this.alertRepository = alertRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public WaterLowUpdateResult report(String deviceUid, boolean waterLow, LocalDateTime reportedAt) {
        IotDevice device = iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull(deviceUid).orElse(null);
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
        Plant plant = findAssignedPlant(robot.getId());

        if (becameLow) {
            if (plant != null) {
                openAlert(robot, plant, reportedAt);
            } else {
                eventPublisher.publishEvent(new StationWaterLowEvent(
                        robot.getUser().getId(),
                        robot.getId(),
                        station.getStationCode()
                ));
            }
            return WaterLowUpdateResult.BECAME_LOW;
        }
        if (waterLow) {
            return WaterLowUpdateResult.STILL_LOW;
        }
        if (wasLow) {
            if (plant != null) {
                resolveAlert(plant.getId());
            }
            return WaterLowUpdateResult.CLEARED;
        }
        return WaterLowUpdateResult.ALREADY_CLEAR;
    }

    /**
     * 로봇이 현재 담당하는 식물이다. 없거나 삭제됐으면 null 이다.
     *
     * <p>부족 중에 배정이 바뀌면 이전 식물의 활성 알림이 남는 것을 감수한다. 해제 보고가 올 때
     * 현재 식물 기준으로만 닫기 때문인데, 그 알림이 새 알림을 막지는 않는다 — 활성 유일성
     * ({@code active_key})은 식물·지표별이라 새 식물에는 새로 열린다.
     */
    private Plant findAssignedPlant(String robotId) {
        return assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robotId)
                .map(PlantDeviceAssignment::getPlantId)
                .flatMap(plantId -> plantRepository.findByIdAndStatusNot(plantId, PlantStatus.DELETED))
                .orElse(null);
    }

    /**
     * 물 부족 알림을 열고 발송 경로에 알린다.
     *
     * <p>이미 열려 있으면 아무것도 하지 않는다. 브로커 재시작 등으로 스테이션 플래그와 알림
     * 상태가 어긋났을 때 {@code active_key} UNIQUE 위반으로 수집이 죽는 것보다, 조용히
     * 합류하는 편이 낫다.
     */
    private void openAlert(Robot robot, Plant plant, LocalDateTime occurredAt) {
        if (alertRepository
                .findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                        plant.getId(), AlertMetricType.STATION_WATER_LOW)
                .isPresent()) {
            return;
        }
        Alert alert = Alert.openStationWaterLow(robot.getUser().getId(), plant.getId(), occurredAt);
        alertRepository.save(alert);
        eventPublisher.publishEvent(new AlertOpenedEvent(
                alert.getId(),
                alert.getUserId(),
                alert.getPlantId(),
                plant.getNickname(),
                AlertMetricType.STATION_WATER_LOW,
                AlertDeviation.LOW
        ));
    }

    /** 보충 보고로 활성 알림을 닫는다. 해제는 푸시하지 않는다 — 이상 알림과 같은 방침이다. */
    private void resolveAlert(String plantId) {
        alertRepository
                .findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                        plantId, AlertMetricType.STATION_WATER_LOW)
                .ifPresent(alert -> alert.resolve(nowUtc()));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
