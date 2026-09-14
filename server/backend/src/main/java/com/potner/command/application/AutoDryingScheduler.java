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
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 낮 동안 일정한 간격으로 로봇을 스테이션에 데려가 팬으로 주변 공기를 순환시킨다.
 *
 * <pre>
 * 마지막 송풍 후 간격이 참  →  이동(스테이션) → 송풍 → 이동(대기 장소)
 * </pre>
 *
 * <p><strong>습도 알림이 조건이 아니다.</strong> 원래는 고습 알림이 열려 있는 동안에만 돌렸는데,
 * 그러면 두 가지가 어긋난다.
 *
 * <ul>
 *   <li>습도가 정상인 날에는 하루 0회다. 그런데 공기 순환의 이득 절반은 습도와 무관하다 —
 *       바람에 흔들린 줄기는 굵고 짧아지고(웃자람 억제), 잎 표면의 정체된 공기층이 걷혀야
 *       증산이 이어진다. 증산이 멈추면 뿌리가 물과 양분을 못 끌어올린다.</li>
 *   <li>알림이 열려 있는 동안 검사 주기마다 돌리므로 하루치 가동이 한 시간에 몰렸다.
 *       10분 주기 × 상한 6회 = 60분이면 그날 몫이 끝났다.</li>
 * </ul>
 *
 * <p>그래서 "습해지면 대응" 이 아니라 <strong>"하루 내내 고르게, 위험할 때 한 번 더"</strong> 로
 * 간다. 곰팡이는 생기고 나서 말리는 것보다 안 생기게 하는 편이 훨씬 쉽다.
 *
 * <p><strong>가장 필요한 한 번은 여기서 돌지 않는다.</strong> 급수 직후가 표토와 잎이 젖어
 * 곰팡이에 가장 취약한 순간인데, 그때는 로봇이 이미 스테이션에 서 있다. 그 한 번은
 * {@link AutoWateringOrchestrator} 의 체인이 이동 없이 끼워 넣고, 이쪽은 그 시각을 간격에
 * 반영해 곧바로 또 돌리지 않는다.
 *
 * <p><strong>송풍이 습도를 낮추지는 않는다.</strong> 목적이 제습이 아니라 공기 순환이라
 * "해소될 때까지" 라는 종료 조건 자체가 성립하지 않는다. 그래서 간격과 하루 상한이 종료를
 * 대신한다.
 *
 * <p>팬이 스테이션에 고정되어 있어 송풍도 이동이 앞선다. 급수·촬영과 같은 모양의 체인이다.
 * 예전에는 송풍 사이가 10분이라 스테이션에 머물렀지만, 이제 몇 시간씩 벌어지므로 매번
 * 대기 장소로 돌아온다 — 왕복 몇 분이 아까워 로봇을 몇 시간 세워 둘 이유가 없다.
 */
@Component
public class AutoDryingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoDryingScheduler.class);

    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final DeviceCommandRepository commandRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final RobotBusyGuard busyGuard;
    private final DeviceCommandService commandService;
    private final DeviceCommandProperties properties;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public AutoDryingScheduler(
            PlantDeviceAssignmentRepository assignmentRepository,
            DeviceCommandRepository commandRepository,
            SensorReadingRepository sensorReadingRepository,
            RobotBusyGuard busyGuard,
            DeviceCommandService commandService,
            DeviceCommandProperties properties,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.assignmentRepository = assignmentRepository;
        this.commandRepository = commandRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.busyGuard = busyGuard;
        this.commandService = commandService;
        this.properties = properties;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${potner.device-command.drying-check-interval-seconds}",
            timeUnit = TimeUnit.SECONDS
    )
    public void dry() {
        if (!properties.autoDryingEnabled()) {
            return;
        }
        dryOnce();
    }

    /** 테스트와 스케줄러가 함께 쓴다. 환기를 시작했거나 복귀시킨 식물 수를 돌려준다. */
    public int dryOnce() {
        // 팬은 스테이션의 라즈베리에 있다. 라즈베리 없는 로봇의 식물은 여기서부터 대상이 아니다.
        List<AssignedDeviceView> targets = assignmentRepository.findActiveAssignedDevices(
                IotDeviceType.RASPBERRY_PI,
                PlantStatus.DELETED
        );

        Set<String> started = new LinkedHashSet<>();
        int acted = 0;
        for (AssignedDeviceView target : targets) {
            String plantId = target.getPlantId();
            if (runStep(plantId, () -> ventilate(plantId))) {
                started.add(plantId);
                acted++;
            }
        }

        // 복귀는 시간대 밖에서도 돈다. 환기를 멈추는 것과 로봇을 밤새 세워 두는 것은 다르다.
        for (String plantId : strandedCandidates(started)) {
            acted += runStep(plantId, () -> sendHome(plantId)) ? 1 : 0;
        }
        return acted;
    }

    /**
     * 이번 주기에 환기할 식물인지 보고, 맞으면 체인을 시작한다.
     *
     * <p>검사 순서가 싼 것부터다. 로봇이 바쁘거나 시간대가 아니면 DB 를 더 볼 이유가 없다.
     */
    private boolean ventilate(String plantId) {
        if (busyGuard.isBusy(plantId)) {
            return false;
        }
        if (!isInVentilationWindow()) {
            return false;
        }
        if (!intervalElapsed(plantId)) {
            return false;
        }
        if (reachedDailyLimit(plantId)) {
            return false;
        }
        if (isTooCold(plantId)) {
            return false;
        }

        // 다른 체인이 방금 스테이션에 두고 간 경우다. 다시 데려갈 것 없이 바로 돌린다.
        if (isAtStation(plantId)) {
            commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                    DeviceCommandType.FAN, null, null), CommandPurpose.DRYING);
            log.info("Auto ventilation: fanning at station: plantId={}", plantId);
            return true;
        }

        commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                DeviceCommandType.NAVIGATE, RobotLocationType.WATER_STATION, null),
                CommandPurpose.DRYING);
        log.info("Auto ventilation run started: plantId={}", plantId);
        return true;
    }

    /**
     * 환기 체인의 다음 단계를 잇는다. 급수·촬영 체인과 같은 모양이라 판단 기준도 같다 —
     * {@code purpose} 로 자기 체인만 이어받는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(DeviceCommandCompletedEvent event) {
        if (event.initiator() != CommandInitiator.AUTO
                || event.purpose() != CommandPurpose.DRYING
                || !properties.autoDryingEnabled()) {
            return;
        }
        // 급수 체인과 같은 기준을 쓴다. 오늘 SKIPPED 를 보내는 것은 급수뿐이라 이 체인에는
        // 닿지 않지만, 판단 기준이 체인마다 갈리면 장치가 상태를 늘릴 때 한 곳만 고쳐진다.
        if (!event.status().continuesChain()) {
            // 여기서 복귀를 보내지 않는다. 이동이 실패했으면 로봇이 어디 있는지 모르고,
            // 송풍이 실패했으면 스테이션에 있으므로 다음 주기의 복귀 단계가 데려온다.
            log.warn(
                    "Auto ventilation run stopped: plantId={}, type={}, status={}",
                    event.plantId(), event.commandType(), event.status()
            );
            return;
        }
        try {
            continueChain(event);
        } catch (RuntimeException exception) {
            log.warn(
                    "Auto ventilation run could not continue: plantId={}",
                    event.plantId(), exception
            );
        }
    }

    private void continueChain(DeviceCommandCompletedEvent event) {
        if (event.commandType() == DeviceCommandType.NAVIGATE
                && event.destination() == RobotLocationType.WATER_STATION) {
            commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                    DeviceCommandType.FAN, null, null), CommandPurpose.DRYING);
            log.info("Auto ventilation: arrived at station, fanning: plantId={}", event.plantId());
            return;
        }
        if (event.commandType() == DeviceCommandType.FAN) {
            commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                    DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null),
                    CommandPurpose.DRYING);
            log.info("Auto ventilation: fanned, returning home: plantId={}", event.plantId());
            return;
        }
        if (event.commandType() == DeviceCommandType.NAVIGATE
                && event.destination() == RobotLocationType.HOME) {
            log.info("Auto ventilation run completed: plantId={}", event.plantId());
        }
    }

    /**
     * 스테이션에 남아 있을 수 있는 로봇 후보다.
     *
     * <p>체인이 정상으로 끝나면 복귀까지 스스로 하므로 여기 걸리지 않는다. 송풍이 실패하거나
     * 복귀 명령이 타임아웃된 경우를 위한 안전망이다. 실제 복귀 여부는
     * {@link #sendHome(String)} 이 위치를 확인한 뒤 정한다.
     */
    private List<String> strandedCandidates(Set<String> startedThisRound) {
        LocalDateTime since = nowUtc().minusDays(1);
        return commandRepository
                .findPlantIdsByPurposeSince(CommandPurpose.DRYING, since)
                .stream()
                .filter(plantId -> !startedThisRound.contains(plantId))
                .toList();
    }

    private boolean sendHome(String plantId) {
        if (busyGuard.isBusy(plantId) || !isAtStation(plantId)) {
            return false;
        }
        commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null),
                CommandPurpose.DRYING);
        log.info("Auto ventilation: robot left at station, returning home: plantId={}", plantId);
        return true;
    }

    /** 한 식물의 실패가 나머지를 막지 않게 감싼다. */
    private boolean runStep(String plantId, java.util.function.BooleanSupplier step) {
        try {
            return step.getAsBoolean();
        } catch (BusinessException exception) {
            // 배정 없음, 위치 미설정, 팬 없는 로봇 등.
            log.info("Auto ventilation skipped: plantId={}, code={}", plantId, exception.errorCode());
        } catch (RuntimeException exception) {
            log.warn("Auto ventilation failed: plantId={}", plantId, exception);
        }
        return false;
    }

    /**
     * 마지막 송풍 이후 간격이 찼는지 본다. 하루 가동 횟수를 실제로 정하는 것이 이 검사다.
     *
     * <p>발행 시각으로 센다. 회신이 늦거나 실패한 명령 뒤에 곧바로 다시 돌리면 간격의 뜻이
     * 없어진다.
     */
    private boolean intervalElapsed(String plantId) {
        DeviceCommand lastFan = commandRepository
                .findFirstByPlantIdAndCommandTypeOrderByIssuedAtDesc(plantId, DeviceCommandType.FAN)
                .orElse(null);
        if (lastFan == null) {
            return true;
        }
        return !nowUtc().isBefore(
                lastFan.getIssuedAt().plusSeconds(properties.dryingIntervalSeconds())
        );
    }

    /**
     * 오늘 자동 송풍이 상한에 닿았는지 본다.
     *
     * <p>이력을 세므로 별도 카운터가 없다. 서버가 재시작해도 상한이 유지된다.
     *
     * <p>하루 경계는 서비스 타임존이다. UTC 자정으로 끊으면 KST 오전 9시에 리셋되어 "어젯밤과
     * 오늘 아침" 이 한 묶음이 된다.
     */
    private boolean reachedDailyLimit(String plantId) {
        long runs = commandRepository.countByPlantIdAndCommandTypeAndIssuedAtAfter(
                plantId, DeviceCommandType.FAN, startOfServiceDayUtc());
        if (runs >= properties.maxAutoDryingRunsPerDay()) {
            log.info("Auto ventilation reached the daily limit: plantId={}, runs={}", plantId, runs);
            return true;
        }
        return false;
    }

    /**
     * 찬 공기를 쐬면 안 되는 온도인지 본다.
     *
     * <p>측정값이 없거나 오래됐으면 <strong>막지 않는다.</strong> 온도 센서 하나가 죽었다고
     * 환기가 통째로 멈추면 손해가 더 크고, 송풍은 잘못 돌아도 해가 적은 동작이다.
     */
    private boolean isTooCold(String plantId) {
        SensorReading latest = sensorReadingRepository
                .findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                        plantId, SensorType.TEMPERATURE, SensorQuality.GOOD)
                .orElse(null);
        if (latest == null) {
            return false;
        }
        LocalDateTime freshSince = nowUtc()
                .minusMinutes(sensorQueryProperties.freshnessThresholdMinutes());
        if (latest.getMeasuredAt().isBefore(freshSince)) {
            return false;
        }
        if (latest.getMeasuredValue()
                .compareTo(BigDecimal.valueOf(properties.dryingMinTemperatureC())) >= 0) {
            return false;
        }
        log.info(
                "Auto ventilation skipped because it is too cold: plantId={}, temperature={}",
                plantId, latest.getMeasuredValue()
        );
        return true;
    }

    /**
     * 로봇이 스테이션에 서 있는지 본다. 마지막으로 성공한 이동의 목적지로 추정한다 —
     * 햇빛 재배치와 같은 방식이다.
     */
    private boolean isAtStation(String plantId) {
        return commandRepository
                .findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
                        plantId, DeviceCommandType.NAVIGATE, DeviceCommandStatus.OK)
                .map(command -> command.getDestination() == RobotLocationType.WATER_STATION)
                .orElse(false);
    }

    /** 서비스 타임존 기준 현재 시(hour)가 환기 시간대 안인지 본다. */
    private boolean isInVentilationWindow() {
        int hour = serviceNow().getHour();
        return hour >= properties.dryingWindowStartHour()
                && hour < properties.dryingWindowEndHour();
    }

    /** 서비스 타임존 기준 오늘 0시를, 저장된 값과 비교할 수 있도록 UTC 로 되돌린 시각이다. */
    private LocalDateTime startOfServiceDayUtc() {
        LocalDate serviceToday = serviceNow().toLocalDate();
        return serviceToday.atStartOfDay().minusSeconds(sensorQueryProperties.zoneOffsetSeconds());
    }

    private LocalDateTime serviceNow() {
        return nowUtc().plusSeconds(sensorQueryProperties.zoneOffsetSeconds());
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
