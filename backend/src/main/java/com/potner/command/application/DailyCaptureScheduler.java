package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.photo.domain.PhotoSource;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <p>이동 중에는 찍지 않는다. 자동 급수나 햇빛 재배치로 로봇이 움직이는 중이면 흔들리거나
 * 엉뚱한 각도가 나온다. 다음 주기에 자리를 잡은 뒤 찍는다.
 */
@Component
public class DailyCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyCaptureScheduler.class);

    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final PlantPhotoRepository photoRepository;
    private final DeviceCommandRepository commandRepository;
    private final DeviceCommandService commandService;
    private final DeviceCommandProperties properties;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public DailyCaptureScheduler(
            PlantDeviceAssignmentRepository assignmentRepository,
            PlantPhotoRepository photoRepository,
            DeviceCommandRepository commandRepository,
            DeviceCommandService commandService,
            DeviceCommandProperties properties,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.assignmentRepository = assignmentRepository;
        this.photoRepository = photoRepository;
        this.commandRepository = commandRepository;
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
        // 앞선 촬영 명령이 아직 회신을 기다린다. 지금 또 보내면 409 다.
        if (commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                plantId, DeviceCommandType.CAPTURE, DeviceCommandStatus.ISSUED)) {
            return false;
        }
        // 이동 중이면 흔들리거나 엉뚱한 각도가 나온다. 자리를 잡은 다음 주기에 찍는다.
        if (commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                plantId, DeviceCommandType.NAVIGATE, DeviceCommandStatus.ISSUED)) {
            return false;
        }

        commandService.issueAuto(plantId, new IssueDeviceCommandRequest(
                DeviceCommandType.CAPTURE, null, null));
        log.info("Daily capture issued: plantId={}, date={}", plantId, today);
        return true;
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
