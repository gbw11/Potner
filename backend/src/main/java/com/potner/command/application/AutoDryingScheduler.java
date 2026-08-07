package com.potner.command.application;

import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.location.domain.RobotLocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 공기가 습하면 송풍으로 순환시킨다.
 *
 * <pre>
 * 습도 HIGH 알림이 열려 있음  →  이동(스테이션) → 송풍 → 이동(대기 장소)
 * 습도가 내려가면 알림이 스스로 닫히고 → 다음 주기부터 아무것도 하지 않는다
 * </pre>
 *
 * <p><strong>판정 지표가 토양수분이 아니라 공기 습도다.</strong> 원래는 토양 과습을 말리는
 * 기능이었는데, 자동 급수가 권장량만 주고 라즈베리 쪽에도 "이미 젖었으면 급수 생략" 게이트가
 * 있어 시스템이 스스로 과습을 만드는 경로가 없었다. 그 알림은 사람이 직접 물을 더 줬을 때만
 * 열리므로 자동화의 대상으로 삼기에 근거가 약했다.
 *
 * <p>반면 공기 습도는 환경이 정하는 값이라 시스템이 통제할 수 없고, 고습에서 공기를 순환시키는
 * 것은 곰팡이와 웃자람을 막는 원예의 기본이다. 습도는 스테이션의 라즈베리가 재므로 판정 근거도
 * 이미 들어와 있다.
 *
 * <p><strong>송풍이 습도를 낮추지는 않는다.</strong> 목적이 제습이 아니라 공기 순환이라
 * "해소될 때까지" 가 아니라 "습한 동안 주기적으로" 가 맞는 종료 모델이고, 그 상한은 아래
 * 하루 가동 횟수가 맡는다. 알림은 환기·제습기 등으로 환경이 바뀌면 닫힌다.
 *
 * <p><strong>알림을 상태 머신으로 재사용한다.</strong> 송풍은 "짧게 돌리고 → 다시 재고
 * → 필요하면 또" 의 반복이라 한 번의 체인으로 끝나지 않는다. 그런데 그 반복 조건이 이미
 * {@code alert} 에 있다 — 고습 알림이 열려 있으면 아직 습한 것이고, 히스테리시스를 넘어
 * 내려가면 서버가 알림을 닫는다. 그래서 별도의 진행 상태를 저장하지 않고 주기 검사로 구현한다.
 *
 * <p>팬이 스테이션에 고정되어 있어 송풍도 이동이 앞선다. 급수와 달리 <strong>송풍하는 동안 굳이
 * 오가지 않는다</strong> — 대기 장소로 돌아왔다가 다음 주기에 또 오는 것은 헛걸음이라, 스테이션에
 * 머문 채로 반복 송풍하고 습도가 내려갔을 때만 복귀시킨다.
 *
 * <p>하루 가동 횟수에 상한을 둔다. 송풍으로는 습도가 내려가지 않으므로 장마철처럼 하루 종일
 * 습한 날에는 알림이 종일 열려 있다. 센서 고장으로 높은 값이 계속 올라오는 경우도 마찬가지다.
 * 물리 장치를 반복 구동하는 자동화에는 스스로 멈추는 지점이 있어야 한다.
 */
@Component
public class AutoDryingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoDryingScheduler.class);

    private final AlertRepository alertRepository;
    private final DeviceCommandRepository commandRepository;
    private final RobotBusyGuard busyGuard;
    private final DeviceCommandService commandService;
    private final DeviceCommandProperties properties;
    private final Clock clock;

    public AutoDryingScheduler(
            AlertRepository alertRepository,
            DeviceCommandRepository commandRepository,
            RobotBusyGuard busyGuard,
            DeviceCommandService commandService,
            DeviceCommandProperties properties,
            Clock clock
    ) {
        this.alertRepository = alertRepository;
        this.commandRepository = commandRepository;
        this.busyGuard = busyGuard;
        this.commandService = commandService;
        this.properties = properties;
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

    /** 테스트와 스케줄러가 함께 쓴다. 송풍을 시작·계속하거나 복귀시킨 식물 수를 돌려준다. */
    public int dryOnce() {
        // 공기 습도로 판정한다. 토양수분이 아닌 이유는 클래스 주석에 있다.
        List<String> humid = alertRepository
                .findAllByMetricTypeAndDeviationAndResolvedAtIsNull(
                        AlertMetricType.HUMIDITY, AlertDeviation.HIGH)
                .stream()
                .map(alert -> alert.getPlantId())
                .distinct()
                .toList();

        int acted = 0;
        for (String plantId : humid) {
            acted += runStep(plantId, () -> dryPlant(plantId)) ? 1 : 0;
        }
        for (String plantId : dryingFinished(humid)) {
            acted += runStep(plantId, () -> sendHome(plantId)) ? 1 : 0;
        }
        return acted;
    }

    /**
     * 송풍이 끝났는데 아직 스테이션에 서 있는 로봇을 되돌린다.
     *
     * <p>이 단계가 없으면 로봇이 스테이션에 방치된다. 습도가 내려가면 알림이 닫히는데 해제는
     * 이벤트를 내지 않으므로, 마지막 송풍 회신 이후에 알림이 닫힌 경우 아무도 복귀를 시키지
     * 않는다. 주기 검사가 그 틈을 메운다.
     */
    private List<String> dryingFinished(List<String> stillHumid) {
        LocalDateTime since = nowUtc().minusDays(1);
        return commandRepository
                .findPlantIdsByPurposeSince(CommandPurpose.DRYING, since)
                .stream()
                .filter(plantId -> !stillHumid.contains(plantId))
                .toList();
    }

    private boolean sendHome(String plantId) {
        if (busyGuard.isBusy(plantId) || !isAtStation(plantId)) {
            return false;
        }
        commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null),
                CommandPurpose.DRYING);
        log.info("Auto drying finished, returning home: plantId={}", plantId);
        return true;
    }

    /** 한 식물의 실패가 나머지를 막지 않게 감싼다. */
    private boolean runStep(String plantId, java.util.function.BooleanSupplier step) {
        try {
            return step.getAsBoolean();
        } catch (BusinessException exception) {
            // 배정 없음, 위치 미설정, 팬 없는 로봇 등.
            log.info("Auto drying skipped: plantId={}, code={}", plantId, exception.errorCode());
        } catch (RuntimeException exception) {
            log.warn("Auto drying failed: plantId={}", plantId, exception);
        }
        return false;
    }

    private boolean dryPlant(String plantId) {
        if (busyGuard.isBusy(plantId)) {
            return false;
        }
        if (reachedDailyLimit(plantId)) {
            return false;
        }

        // 이미 스테이션에 있으면(직전 자동 이동이 거기였으면) 다시 데려가지 않고 바로 돌린다.
        if (isAtStation(plantId)) {
            commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                    DeviceCommandType.FAN, null, null), CommandPurpose.DRYING);
            log.info("Auto drying: fanning at station: plantId={}", plantId);
            return true;
        }

        commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                DeviceCommandType.NAVIGATE, RobotLocationType.WATER_STATION, null),
                CommandPurpose.DRYING);
        log.info("Auto drying run started: plantId={}", plantId);
        return true;
    }

    /**
     * 말리기 체인의 다음 단계를 잇는다.
     *
     * <p>송풍이 끝나도 여기서 복귀시키지 않는다. 아직 습하면 다음 주기가 그 자리에서 다시
     * 돌리고, 내려가면 {@link #dryOnce()} 의 복귀 단계가 데려온다. 매 회차 오가면 이동이 송풍보다
     * 오래 걸린다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(DeviceCommandCompletedEvent event) {
        if (event.initiator() != CommandInitiator.AUTO
                || event.purpose() != CommandPurpose.DRYING
                || !properties.autoDryingEnabled()) {
            return;
        }
        if (event.status() != DeviceCommandStatus.OK) {
            log.warn(
                    "Auto drying run stopped: plantId={}, type={}, status={}",
                    event.plantId(), event.commandType(), event.status()
            );
            return;
        }
        try {
            if (event.commandType() == DeviceCommandType.NAVIGATE
                    && event.destination() == RobotLocationType.WATER_STATION) {
                commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                        DeviceCommandType.FAN, null, null), CommandPurpose.DRYING);
                log.info("Auto drying: arrived at station, fanning: plantId={}", event.plantId());
            }
        } catch (RuntimeException exception) {
            log.warn("Auto drying run could not continue: plantId={}", event.plantId(), exception);
        }
    }

    /**
     * 로봇이 스테이션에 서 있는지 본다. 마지막으로 성공한 자동 이동의 목적지로 추정한다 —
     * 햇빛 재배치와 같은 방식이다.
     */
    private boolean isAtStation(String plantId) {
        return commandRepository
                .findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
                        plantId, DeviceCommandType.NAVIGATE, DeviceCommandStatus.OK)
                .map(command -> command.getDestination() == RobotLocationType.WATER_STATION)
                .orElse(false);
    }

    /**
     * 오늘 자동 송풍이 상한에 닿았는지 본다.
     *
     * <p>이력을 세므로 별도 카운터가 없다. 서버가 재시작해도 상한이 유지된다.
     */
    private boolean reachedDailyLimit(String plantId) {
        LocalDateTime startOfDay = nowUtc().toLocalDate().atStartOfDay();
        long runs = commandRepository.countByPlantIdAndCommandTypeAndPurposeAndIssuedAtAfter(
                plantId, DeviceCommandType.FAN, CommandPurpose.DRYING, startOfDay);
        if (runs >= properties.maxAutoDryingRunsPerDay()) {
            log.info(
                    "Auto drying reached the daily limit: plantId={}, runs={}",
                    plantId, runs
            );
            return true;
        }
        return false;
    }

    /** 상한은 UTC 자정 기준이다. 서비스 타임존으로 끊을 만큼 정밀할 필요가 없는 안전장치다. */
    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
