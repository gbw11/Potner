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
 * 토양 과습을 송풍으로 말린다. 자동 급수의 대칭이다.
 *
 * <pre>
 * 토양수분 HIGH 알림이 열려 있음  →  이동(스테이션) → 송풍 → 이동(대기 장소)
 * 흙이 마르면 알림이 스스로 닫히고 → 다음 주기부터 아무것도 하지 않는다
 * </pre>
 *
 * <p><strong>알림을 상태 머신으로 재사용한다.</strong> 말리기는 "짧게 돌리고 → 수분을 다시 재고
 * → 필요하면 또" 의 반복이라 한 번의 체인으로 끝나지 않는다. 그런데 그 반복 조건이 이미
 * {@code alert} 에 있다 — 과습 알림이 열려 있으면 아직 젖은 것이고, 히스테리시스를 넘어 마르면
 * 서버가 알림을 닫는다. 그래서 별도의 진행 상태를 저장하지 않고 주기 검사로 구현한다.
 *
 * <p>팬이 스테이션에 고정되어 있어 송풍도 이동이 앞선다. 급수와 달리 <strong>말리는 동안 굳이
 * 오가지 않는다</strong> — 대기 장소로 돌아왔다가 다음 주기에 또 오는 것은 헛걸음이라, 스테이션에
 * 머문 채로 반복 송풍하고 과습이 풀렸을 때만 복귀시킨다.
 *
 * <p>하루 가동 횟수에 상한을 둔다. 알림이 닫히는 것이 정상 종료지만, 수분 센서가 고장 나
 * 계속 높은 값을 보내면 알림이 영영 열려 있어 팬이 끝없이 돈다. 물리 장치를 반복 구동하는
 * 자동화에는 스스로 멈추는 지점이 있어야 한다.
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
        List<String> overwatered = alertRepository
                .findAllByMetricTypeAndDeviationAndResolvedAtIsNull(
                        AlertMetricType.SOIL_MOISTURE, AlertDeviation.HIGH)
                .stream()
                .map(alert -> alert.getPlantId())
                .distinct()
                .toList();

        int acted = 0;
        for (String plantId : overwatered) {
            acted += runStep(plantId, () -> dryPlant(plantId)) ? 1 : 0;
        }
        for (String plantId : dryingFinished(overwatered)) {
            acted += runStep(plantId, () -> sendHome(plantId)) ? 1 : 0;
        }
        return acted;
    }

    /**
     * 말리기가 끝났는데 아직 스테이션에 서 있는 로봇을 되돌린다.
     *
     * <p>이 단계가 없으면 로봇이 스테이션에 방치된다. 과습이 풀리면 알림이 닫히는데 해제는
     * 이벤트를 내지 않으므로, 마지막 송풍 회신 이후에 알림이 닫힌 경우 아무도 복귀를 시키지
     * 않는다. 주기 검사가 그 틈을 메운다.
     */
    private List<String> dryingFinished(List<String> stillOverwatered) {
        LocalDateTime since = nowUtc().minusDays(1);
        return commandRepository
                .findPlantIdsByPurposeSince(CommandPurpose.DRYING, since)
                .stream()
                .filter(plantId -> !stillOverwatered.contains(plantId))
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
     * <p>송풍이 끝나도 여기서 복귀시키지 않는다. 아직 과습이면 다음 주기가 그 자리에서 다시
     * 돌리고, 마르면 {@link #dryOnce()} 의 복귀 단계가 데려온다. 매 회차 오가면 이동이 송풍보다
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
