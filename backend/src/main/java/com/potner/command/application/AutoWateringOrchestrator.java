package com.potner.command.application;

import com.potner.alert.application.AlertOpenedEvent;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 수분 부족을 자동으로 해결하는 케어 체인이다.
 *
 * <pre>
 * 토양수분 알림 열림 → 이동(급수 스테이션) → OK → 급수 → OK → 송풍 → OK → 이동(대기 장소) → 끝
 * </pre>
 *
 * <p>급수 뒤에 송풍 한 단계가 붙는다. 물을 준 직후가 표토와 잎이 젖어 곰팡이에 가장 취약한
 * 순간인데, 팬이 있는 스테이션에 로봇이 이미 서 있으므로 이동이 늘지 않는다. 주기 환기
 * ({@link AutoDryingScheduler}) 는 이 발행 시각을 간격에 반영해 곧바로 또 돌리지 않는다.
 *
 * <p>체인 상태를 따로 저장하지 않는다. 다음 단계는 회신된 명령의 속성(종류·목적지·발행 주체)
 * 만으로 정해지므로, 상태 테이블을 두면 명령 이력과 어긋날 자리만 생긴다. 서버가 재시작해도
 * 회신이 도착하면 체인이 그대로 이어진다.
 *
 * <p>단계가 하나라도 실패(ERROR/BUSY)하거나 회신이 없으면(TIMED_OUT — 이벤트 자체가 없다)
 * 체인은 거기서 멈춘다. 재시도하지 않는 이유: 수분 부족 알림이 아직 열려 있으므로 사용자는
 * 이미 문제를 알고 있고, 실패한 하드웨어에 명령을 반복하면 급수량 이중 집행 같은 더 나쁜
 * 실패가 생긴다. 다음 시도는 알림이 해제됐다가 다시 열릴 때다.
 *
 * <p>같은 알림 에피소드에 체인은 한 번만 뜬다. 활성 알림은 식물·지표별 1건이라
 * ({@code active_key}) 알림이 다시 열리려면 먼저 정상 복귀해야 하기 때문이다.
 *
 * <p>발행 주체({@code AUTO})가 아닌 명령의 회신은 건드리지 않는다. 사용자가 직접 누른 이동의
 * 회신에 서버가 급수를 이어 붙이면, 사용자는 시키지 않은 동작을 보게 된다.
 */
@Component
public class AutoWateringOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AutoWateringOrchestrator.class);

    private final DeviceCommandService commandService;
    private final PlantGrowthProfileRepository profileRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final RobotLocationRepository locationRepository;
    private final DeviceCommandProperties properties;

    public AutoWateringOrchestrator(
            DeviceCommandService commandService,
            PlantGrowthProfileRepository profileRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            RobotLocationRepository locationRepository,
            DeviceCommandProperties properties
    ) {
        this.commandService = commandService;
        this.profileRepository = profileRepository;
        this.assignmentRepository = assignmentRepository;
        this.locationRepository = locationRepository;
        this.properties = properties;
    }

    /**
     * 토양수분 부족 알림이 열리면 체인을 시작한다.
     *
     * <p>커밋 이후에 돈다 — 알림 저장이 확정되기 전에 로봇이 움직이면 안 된다. 발행 자체는
     * {@code issueAuto} 가 새 트랜잭션({@code REQUIRES_NEW})으로 저장하므로 커밋된다.
     * 이 스레드는 MQTT 콜백 스레드지만 여기서 하는 일은 짧은 DB 작업뿐이고, 실제 브로커 발행은
     * 명령 트랜잭션의 커밋 이후 리스너가 맡는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertOpened(AlertOpenedEvent event) {
        if (event.metricType() != AlertMetricType.SOIL_MOISTURE
                || event.deviation() != AlertDeviation.LOW
                || !properties.autoWaterEnabled()) {
            return;
        }
        try {
            if (!readyToStart(event.plantId())) {
                return;
            }
            commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                    DeviceCommandType.NAVIGATE, RobotLocationType.WATER_STATION, null),
                    CommandPurpose.WATERING);
            log.info("Auto watering run started: plantId={}", event.plantId());
        } catch (BusinessException exception) {
            // 배정 없음, 위치 미설정, 앞선 명령 대기 중 등. 시작을 못 한 이유가 로그로 남아야
            // "알림은 왔는데 로봇이 왜 안 움직이지" 의 원인을 찾을 수 있다.
            log.info(
                    "Auto watering run not started: plantId={}, code={}",
                    event.plantId(),
                    exception.errorCode()
            );
        } catch (RuntimeException exception) {
            log.warn("Auto watering run failed to start: plantId={}", event.plantId(), exception);
        }
    }

    /**
     * 급수 체인 명령의 회신이 반영되면 다음 단계를 잇는다.
     *
     * <p>{@code purpose} 로 좁히는 것이 중요하다. 촬영·말리기 체인도 같은 스테이션 이동을 쓰므로,
     * 종류와 목적지만 보면 촬영하러 도착한 로봇에 급수 명령이 나간다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(DeviceCommandCompletedEvent event) {
        if (event.initiator() != CommandInitiator.AUTO
                || event.purpose() != CommandPurpose.WATERING
                || !properties.autoWaterEnabled()) {
            return;
        }
        // 과습 가드가 막은 급수(SKIPPED)는 여기서 멈추지 않는다. 물을 주지 않았을 뿐 로봇은
        // 스테이션에 멀쩡히 서 있으므로 송풍과 복귀는 그대로 이어져야 한다.
        if (!event.status().continuesChain()) {
            log.warn(
                    "Auto watering run stopped: plantId={}, step={}, status={}",
                    event.plantId(),
                    stepLabel(event),
                    event.status()
            );
            // 송풍은 급수에 딸린 부가 단계라 실패해도 로봇은 돌려보내야 한다. 여기서 멈추면
            // 팬 하나가 고장 났을 때 로봇이 스테이션에 남는다. 급수·이동 실패는 로봇이 어디
            // 있는지 알 수 없으므로 지금처럼 멈춘다.
            if (event.commandType() == DeviceCommandType.FAN) {
                returnHomeQuietly(event);
            }
            return;
        }
        try {
            continueChain(event);
        } catch (BusinessException exception) {
            log.warn(
                    "Auto watering run could not continue: plantId={}, step={}, code={}",
                    event.plantId(),
                    stepLabel(event),
                    exception.errorCode()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Auto watering run failed to continue: plantId={}, step={}",
                    event.plantId(),
                    stepLabel(event),
                    exception
            );
        }
    }

    private void continueChain(DeviceCommandCompletedEvent event) {
        if (event.commandType() == DeviceCommandType.NAVIGATE
                && event.destination() == RobotLocationType.WATER_STATION) {
            commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                    DeviceCommandType.WATER, null, null), CommandPurpose.WATERING);
            log.info("Auto watering run: arrived at station, watering: plantId={}", event.plantId());
            return;
        }
        // 급수 직후가 곰팡이에 가장 취약한 순간이다. 표토가 젖고 잎에 물이 튀고 주변 습도가
        // 오르는데, 그때 로봇은 이미 팬이 있는 스테이션에 서 있다. 주기 환기로 따로 오려면
        // 왕복이 한 번 더 드는 그 한 번을 여기서 이동 없이 끼워 넣는다.
        if (event.commandType() == DeviceCommandType.WATER) {
            if (properties.autoDryingEnabled()) {
                commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                        DeviceCommandType.FAN, null, null), CommandPurpose.WATERING);
                log.info("Auto watering run: watered, ventilating: plantId={}", event.plantId());
                return;
            }
            returnHome(event, "watered");
            return;
        }
        if (event.commandType() == DeviceCommandType.FAN) {
            returnHome(event, "ventilated");
            return;
        }
        if (event.commandType() == DeviceCommandType.NAVIGATE
                && event.destination() == RobotLocationType.HOME) {
            log.info("Auto watering run completed: plantId={}", event.plantId());
        }
        // 그 외 조합은 이 체인이 발행하지 않으므로 없다. 생기면 무시한다.
    }

    private void returnHome(DeviceCommandCompletedEvent event, String afterStep) {
        commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null),
                CommandPurpose.WATERING);
        log.info("Auto watering run: {}, returning home: plantId={}", afterStep, event.plantId());
    }

    /** 실패 경로의 복귀다. 이미 실패를 로그로 남긴 뒤라 여기서 또 예외를 밖으로 내지 않는다. */
    private void returnHomeQuietly(DeviceCommandCompletedEvent event) {
        try {
            returnHome(event, "ventilation failed");
        } catch (RuntimeException exception) {
            log.warn("Auto watering run could not return home: plantId={}", event.plantId(), exception);
        }
    }

    /**
     * 로봇을 보내기 전에, 보내 봐야 소용없는 조건을 거른다.
     *
     * <p>급수량 미설정은 지금 걸러야 한다. 이동 명령 자체는 급수량 없이도 발행되므로, 여기서
     * 안 보면 로봇이 스테이션까지 간 뒤에야 급수 단계가 실패한다.
     *
     * <p>스테이션 물 부족도 마찬가지다. 저수조가 비어 있으면 가 봐야 급수가 안 되고, 사용자는
     * 이미 물 부족 알림을 받았다.
     *
     * <p>위치 미등록·좌표 미설정·배정 없음은 거르지 않는다 — {@code issueAuto} 가 어차피
     * 검사해서 {@code BusinessException} 으로 알려 준다.
     */
    private boolean readyToStart(String plantId) {
        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId).orElse(null);
        if (profile == null || profile.getRecommendedWateringMl() == null) {
            log.info(
                    "Auto watering skipped because no watering amount is configured: plantId={}",
                    plantId
            );
            return false;
        }
        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(plantId)
                .orElse(null);
        if (assignment == null) {
            log.info("Auto watering skipped because no robot is assigned: plantId={}", plantId);
            return false;
        }
        RobotLocation station = locationRepository
                .findByRobotIdAndLocationType(assignment.getRobotId(), RobotLocationType.WATER_STATION)
                .orElse(null);
        if (station != null && station.isWaterLow()) {
            log.info(
                    "Auto watering skipped because the station reports water low: plantId={}",
                    plantId
            );
            return false;
        }
        return true;
    }

    private String stepLabel(DeviceCommandCompletedEvent event) {
        return event.destination() == null
                ? event.commandType().name()
                : event.commandType().name() + ":" + event.destination().name();
    }
}
