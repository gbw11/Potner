package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.light.application.DailyLightAggregationService;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 하루 목표 광량을 채우도록 로봇을 옮긴다.
 *
 * <pre>
 * 낮 시간대 + 목표 미달 + 햇빛 자리가 아님  → 이동(햇빛 자리)
 * 햇빛 자리에 있음 + (목표 달성 또는 해 짐)  → 이동(대기 장소 = 그늘)
 * </pre>
 *
 * <p>이동은 하루 최대 두 번이다. 아침에 미달이라 나가고, 채우면(또는 창이 닫히면) 돌아온다.
 * 누적 광량은 줄지 않으므로 달성 후 다시 나가는 왕복이 없다.
 *
 * <p>로봇의 현재 위치는 마지막으로 성공한 이동 명령의 목적지로 추정한다. 로봇은 위치를
 * 보고하지 않고, 서버가 보낸 이동만이 위치를 바꾼다(자율 임무는 끄기로 결정됐다).
 * 이동 이력이 전혀 없으면 어디 있는지 모르므로 "햇빛 자리 아님" 으로 본다 — 미달이면
 * 보내는 쪽이 안전하다.
 *
 * <p>다른 체인이 로봇을 쓰고 있으면 {@link RobotBusyGuard} 로 양보한다. 이번 주기는 건너뛰고,
 * 그 체인이 끝나 로봇이 대기 장소로 돌아오면 다음 주기가 미달 여부를 다시 본다. 촬영·환기와
 * 같은 방침이다.
 */
@Component
public class SunlightRelocationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SunlightRelocationScheduler.class);

    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final DeviceCommandRepository commandRepository;
    private final DailyLightAggregationService aggregationService;
    private final RobotBusyGuard busyGuard;
    private final DeviceCommandService commandService;
    private final DeviceCommandProperties properties;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public SunlightRelocationScheduler(
            PlantDeviceAssignmentRepository assignmentRepository,
            PlantGrowthProfileRepository profileRepository,
            DeviceCommandRepository commandRepository,
            DailyLightAggregationService aggregationService,
            RobotBusyGuard busyGuard,
            DeviceCommandService commandService,
            DeviceCommandProperties properties,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.assignmentRepository = assignmentRepository;
        this.profileRepository = profileRepository;
        this.commandRepository = commandRepository;
        this.aggregationService = aggregationService;
        this.busyGuard = busyGuard;
        this.commandService = commandService;
        this.properties = properties;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${potner.device-command.sunlight-check-interval-seconds}",
            timeUnit = TimeUnit.SECONDS
    )
    public void relocate() {
        if (!properties.autoSunlightEnabled()) {
            return;
        }
        relocateOnce();
    }

    /** 테스트와 스케줄러가 함께 쓴다. 발행한 이동 명령 수를 돌려준다. */
    public int relocateOnce() {
        // 이동은 젯슨이 받는다. 젯슨 없는 로봇의 식물은 여기서부터 대상이 아니다.
        List<AssignedDeviceView> targets = assignmentRepository.findActiveAssignedDevices(
                IotDeviceType.JETSON_ORIN,
                PlantStatus.DELETED
        );
        int moved = 0;
        for (AssignedDeviceView target : targets) {
            try {
                if (relocatePlant(target.getPlantId())) {
                    moved++;
                }
            } catch (BusinessException exception) {
                // 위치 미설정, 앞선 명령 대기 중 등. 한 식물의 문제가 다른 식물의 재배치를
                // 막으면 안 된다.
                log.info(
                        "Sunlight relocation skipped: plantId={}, code={}",
                        target.getPlantId(),
                        exception.errorCode()
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Sunlight relocation failed: plantId={}",
                        target.getPlantId(),
                        exception
                );
            }
        }
        return moved;
    }

    private boolean relocatePlant(String plantId) {
        BigDecimal target = dailyLightTarget(plantId);
        if (target == null) {
            // 종 기준에 목표가 없는 식물(예: 저광 관엽)이다. 판정할 근거가 없으면 옮기지 않는다.
            return false;
        }

        // 로봇이 무언가 하고 있으면 양보한다. 이동만 보면 부족하다 — 급수·송풍·촬영은
        // 이동이 끝난 뒤 다른 종류의 명령으로 진행되므로, 그 구간에는 대기 중인 NAVIGATE 가
        // 없다. 그때 재배치가 끼어들면 펌프가 물을 내보내는 중에 바퀴가 움직인다
        // (펌프·팬·카메라는 라즈베리, 바퀴는 젯슨이라 서로 막지 못한다).
        if (busyGuard.isBusy(plantId)) {
            return false;
        }

        DeviceCommand lastNavigate = commandRepository
                .findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
                        plantId, DeviceCommandType.NAVIGATE, DeviceCommandStatus.OK)
                .orElse(null);
        if (isHeldByManualPlacement(lastNavigate)) {
            return false;
        }

        boolean atSunlight = lastNavigate != null
                && lastNavigate.getDestination() == RobotLocationType.SUNLIGHT;
        boolean fulfilled = aggregationService.accumulateToday(plantId)
                .accumulatedLuxHour()
                .compareTo(target) >= 0;
        boolean inWindow = isInSunlightWindow();

        if (atSunlight && (fulfilled || !inWindow)) {
            commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                    DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null),
                    CommandPurpose.RELOCATION);
            log.info(
                    "Sunlight relocation: returning home: plantId={}, fulfilled={}, inWindow={}",
                    plantId, fulfilled, inWindow
            );
            return true;
        }
        if (!atSunlight && !fulfilled && inWindow) {
            commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                    DeviceCommandType.NAVIGATE, RobotLocationType.SUNLIGHT, null),
                    CommandPurpose.RELOCATION);
            log.info("Sunlight relocation: moving to sunlight: plantId={}", plantId);
            return true;
        }
        return false;
    }

    /**
     * 사용자가 직접 이동시킨 지 얼마 안 됐으면 건드리지 않는다.
     *
     * <p>이게 없으면 사용자가 마중 자리로 부른 로봇을 다음 검사 주기에 서버가 도로 끌고 간다.
     * 홀드 시간이 지나면 자동 재배치가 다시 넘겨받는다.
     */
    private boolean isHeldByManualPlacement(DeviceCommand lastNavigate) {
        if (lastNavigate == null
                || lastNavigate.getInitiator() != CommandInitiator.USER
                || lastNavigate.getReportedAt() == null) {
            return false;
        }
        LocalDateTime holdUntil = lastNavigate.getReportedAt()
                .plus(Duration.ofMinutes(properties.manualPlacementHoldMinutes()));
        return nowUtc().isBefore(holdUntil);
    }

    private BigDecimal dailyLightTarget(String plantId) {
        return profileRepository.findByPlantId(plantId)
                .map(GrowthProfileValues::from)
                .map(GrowthProfileValues::dailyLightTargetLuxHour)
                .orElse(null);
    }

    /** 서비스 타임존 기준 현재 시(hour)가 햇빛 창 안인지 본다. UTC 로 보면 9시간 어긋난다. */
    private boolean isInSunlightWindow() {
        int hour = nowUtc()
                .plusSeconds(sensorQueryProperties.zoneOffsetSeconds())
                .getHour();
        return hour >= properties.sunlightWindowStartHour()
                && hour < properties.sunlightWindowEndHour();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
