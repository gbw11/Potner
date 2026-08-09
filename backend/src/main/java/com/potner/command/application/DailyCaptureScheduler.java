package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.photo.domain.PhotoSource;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
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
 * 하루 한 장 성장 사진을 자동으로 찍는다.
 *
 * <p>사진 업로드는 여러 기능의 유일한 입구다 — 포토 로그, 타임랩스, 생장 단계 판정, 그리고
 * 그 판정에 딸린 자동 개화 기록과 새싹 알림이 모두 사진이 올라와야 시작된다. 촬영을 사용자
 * 조작에만 맡기면 그 전부가 비어 있게 된다.
 *
 * <p>고정 시각에 한 번 쏘지 않고 <strong>낮 동안 주기적으로 검사</strong>한다. 정해진 순간에
 * 로봇이 꺼져 있거나 이동 중이면 그날 사진을 영영 잃는데, 주기 검사는 조건이 갖춰지는 대로
 * 촬영해 스스로 만회한다. "오늘 사진이 있는지" 를 매번 보므로 여러 번 돌아도 한 장만 남는다.
 *
 * <p>시간대를 낮으로 한정하는 이유가 둘이다. 어두우면 사진이 쓸모없고, 생장 단계 추론이 어두운
 * 사진에서 오탐을 낸다. 끝 시각은 일기 배치(21시)보다 충분히 앞서야 그날 사진이 일기에 실린다.
 *
 * <p><strong>카메라가 스테이션에 고정되어 있어 촬영도 이동이 앞선다.</strong> 식물은 젯슨에
 * 실려 다니므로, 로봇을 스테이션으로 보내지 않고 촬영하면 빈 스테이션 사진이 남는다.
 * 급수와 같은 모양의 체인이다 — 이동 → 촬영 → 복귀.
 *
 * <p>급수하러 간 김에 겸사겸사 찍지 않는다. 급수는 며칠에 한 번이라 그 주기에 묶으면 타임랩스
 * 프레임 간격이 들쭉날쭉해진다. 사진은 매일 같은 간격이어야 성장이 보인다.
 *
 * <p>다른 체인이 로봇을 쓰고 있으면 이번 주기는 넘긴다({@link RobotBusyGuard}).
 */
@Component
public class DailyCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyCaptureScheduler.class);

    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final PlantPhotoRepository photoRepository;
    private final RobotBusyGuard busyGuard;
    private final DeviceCommandService commandService;
    private final DeviceCommandProperties properties;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public DailyCaptureScheduler(
            PlantDeviceAssignmentRepository assignmentRepository,
            PlantPhotoRepository photoRepository,
            RobotBusyGuard busyGuard,
            DeviceCommandService commandService,
            DeviceCommandProperties properties,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.assignmentRepository = assignmentRepository;
        this.photoRepository = photoRepository;
        this.busyGuard = busyGuard;
        this.commandService = commandService;
        this.properties = properties;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${potner.device-command.capture-check-interval-seconds}",
            timeUnit = TimeUnit.SECONDS
    )
    public void capture() {
        if (!properties.autoCaptureEnabled() || !isInCaptureWindow()) {
            return;
        }
        captureOnce();
    }

    /** 테스트와 스케줄러가 함께 쓴다. 발행한 촬영 명령 수를 돌려준다. */
    public int captureOnce() {
        // 촬영은 라즈베리가 받는다. 카메라 없는 로봇의 식물은 여기서부터 대상이 아니다.
        List<AssignedDeviceView> targets = assignmentRepository.findActiveAssignedDevices(
                IotDeviceType.RASPBERRY_PI,
                PlantStatus.DELETED
        );
        LocalDate today = serviceToday();
        int issued = 0;
        for (AssignedDeviceView target : targets) {
            try {
                if (captureIfNeeded(target.getPlantId(), today)) {
                    issued++;
                }
            } catch (BusinessException exception) {
                // 촬영 명령이 이미 대기 중이거나 장치를 못 찾은 경우다. 한 식물의 문제가 다른
                // 식물의 촬영을 막으면 안 된다.
                log.info(
                        "Daily capture skipped: plantId={}, code={}",
                        target.getPlantId(),
                        exception.errorCode()
                );
            } catch (RuntimeException exception) {
                log.warn("Daily capture failed: plantId={}", target.getPlantId(), exception);
            }
        }
        return issued;
    }

    private boolean captureIfNeeded(String plantId, LocalDate today) {
        // 하루 한 장이다. 이미 오늘 사진이 있으면 장치가 409 로 거절하므로 명령 자체를 아낀다.
        if (photoRepository.existsByPlantIdAndSourceAndPhotoDate(plantId, PhotoSource.DEVICE, today)) {
            return false;
        }
        // 급수나 말리기가 로봇을 쓰고 있다. 끝나면 다음 주기에 다시 본다.
        if (busyGuard.isBusy(plantId)) {
            return false;
        }

        // 카메라가 스테이션에 있으므로 먼저 데려간다. 도착 회신이 오면 체인이 촬영을 잇는다.
        commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                DeviceCommandType.NAVIGATE, RobotLocationType.WATER_STATION, null),
                CommandPurpose.CAPTURE);
        log.info("Daily capture run started: plantId={}, date={}", plantId, today);
        return true;
    }

    /**
     * 촬영 체인의 다음 단계를 잇는다. 급수 체인과 같은 모양이라 판단 기준도 같다 —
     * {@code purpose} 로 자기 체인만 이어받는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(DeviceCommandCompletedEvent event) {
        if (event.initiator() != CommandInitiator.AUTO
                || event.purpose() != CommandPurpose.CAPTURE
                || !properties.autoCaptureEnabled()) {
            return;
        }
        // 급수 체인과 같은 기준을 쓴다. 오늘 SKIPPED 를 보내는 것은 급수뿐이라 이 체인에는
        // 닿지 않지만, 판단 기준이 체인마다 갈리면 장치가 상태를 늘릴 때 한 곳만 고쳐진다.
        if (!event.status().continuesChain()) {
            log.warn(
                    "Daily capture run stopped: plantId={}, type={}, status={}",
                    event.plantId(), event.commandType(), event.status()
            );
            return;
        }
        try {
            continueChain(event);
        } catch (RuntimeException exception) {
            log.warn("Daily capture run could not continue: plantId={}", event.plantId(), exception);
        }
    }

    private void continueChain(DeviceCommandCompletedEvent event) {
        if (event.commandType() == DeviceCommandType.NAVIGATE
                && event.destination() == RobotLocationType.WATER_STATION) {
            commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                    DeviceCommandType.CAPTURE, null, null), CommandPurpose.CAPTURE);
            log.info("Daily capture run: arrived at station, capturing: plantId={}", event.plantId());
            return;
        }
        if (event.commandType() == DeviceCommandType.CAPTURE) {
            commandService.issueAuto(event.plantId(), new IssueDeviceCommandRequest(
                    DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null),
                    CommandPurpose.CAPTURE);
            log.info("Daily capture run: captured, returning home: plantId={}", event.plantId());
            return;
        }
        if (event.commandType() == DeviceCommandType.NAVIGATE
                && event.destination() == RobotLocationType.HOME) {
            log.info("Daily capture run completed: plantId={}", event.plantId());
        }
    }

    /** 날짜 경계는 서비스 타임존 기준이다. UTC 로 끊으면 하루가 9시간 밀린다. */
    private LocalDate serviceToday() {
        return nowUtc().plusSeconds(sensorQueryProperties.zoneOffsetSeconds()).toLocalDate();
    }

    private boolean isInCaptureWindow() {
        int hour = nowUtc().plusSeconds(sensorQueryProperties.zoneOffsetSeconds()).getHour();
        return hour >= properties.captureWindowStartHour()
                && hour < properties.captureWindowEndHour();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
