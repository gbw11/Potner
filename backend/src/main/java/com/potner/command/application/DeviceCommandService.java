package com.potner.command.application;

import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.DeviceCommandListResponse;
import com.potner.command.dto.DeviceCommandResponse;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 장치 명령의 발행과 조회다.
 *
 * <p>발행은 두 단계다. 여기서는 행을 저장하고 이벤트만 낸다. 실제 MQTT 발행은
 * {@link DeviceCommandPublishListener} 가 커밋 뒤에 한다. 커밋 전에 브로커로 나가면 장치의
 * 즉답(BUSY)이 아직 없는 requestId 를 찾다 버려진다.
 *
 * <p>급수량은 사용자가 정하지 않는다. 식물별 적용 생육 기준({@code recommended_watering_ml})이
 * 유일한 출처다. 요청 본문에 양을 받으면 케어 설정과 실제 급수가 어긋난 채로 운영된다.
 */
@Service
public class DeviceCommandService {

    private final DeviceCommandRepository commandRepository;
    private final PlantRepository plantRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final IotDeviceRepository iotDeviceRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final SensorQueryProperties sensorQueryProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public DeviceCommandService(
            DeviceCommandRepository commandRepository,
            PlantRepository plantRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            IotDeviceRepository iotDeviceRepository,
            PlantGrowthProfileRepository profileRepository,
            SensorQueryProperties sensorQueryProperties,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.commandRepository = commandRepository;
        this.plantRepository = plantRepository;
        this.assignmentRepository = assignmentRepository;
        this.iotDeviceRepository = iotDeviceRepository;
        this.profileRepository = profileRepository;
        this.sensorQueryProperties = sensorQueryProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public DeviceCommandResponse issue(String userId, String plantId, DeviceCommandType type) {
        requireOwnedPlant(userId, plantId);

        // 대기 중 재발행을 서버에서 먼저 막는다. 장치도 BUSY 로 거절하지만 그건 이력 행을
        // 하나 더 남기고, 사용자에게는 "왜 안 되는지" 가 회신이 올 때까지 보이지 않는다.
        if (commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                plantId, type, DeviceCommandStatus.ISSUED)) {
            throw new BusinessException(ErrorCode.DEVICE_COMMAND_ALREADY_PENDING);
        }

        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND));

        // 급수도 촬영도 라즈베리가 받는다. 펌프와 카메라가 라즈베리에 달려 있다.
        IotDevice device = iotDeviceRepository
                .findAllByRobotIdOrderByDeviceTypeAsc(assignment.getRobotId())
                .stream()
                .filter(candidate -> candidate.getDeviceType() == IotDeviceType.RASPBERRY_PI)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_DEVICE_NOT_FOUND));

        BigDecimal requestedMl = type == DeviceCommandType.WATER ? wateringAmount(plantId) : null;

        DeviceCommand command = commandRepository.save(DeviceCommand.issue(
                plantId,
                assignment.getRobotId(),
                device.getDeviceUid(),
                type,
                requestedMl,
                now()
        ));

        eventPublisher.publishEvent(new DeviceCommandIssuedEvent(
                command.getRequestId(),
                command.getDeviceUid(),
                command.getCommandType(),
                command.getRequestedMl()
        ));
        return DeviceCommandResponse.from(command);
    }

    @Transactional(readOnly = true)
    public DeviceCommandListResponse getCommands(
            String userId,
            String plantId,
            LocalDate from,
            LocalDate to
    ) {
        requireOwnedPlant(userId, plantId);
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        List<DeviceCommand> commands = commandRepository
                .findAllByPlantIdAndIssuedAtBetweenOrderByIssuedAtDesc(
                        plantId,
                        startOfDayUtc(from),
                        startOfDayUtc(to.plusDays(1)).minusSeconds(1)
                );
        return new DeviceCommandListResponse(
                plantId,
                commands.stream().map(DeviceCommandResponse::from).toList()
        );
    }

    /**
     * 급수량을 적용 생육 기준에서 읽는다.
     *
     * <p>기준에 급수량이 비어 있으면 발행하지 않는다. 기본값으로 아무 양이나 보내면 그 값이
     * 어디서 왔는지 아무도 설명할 수 없고, 화분 크기에 따라 과급수가 된다.
     */
    private BigDecimal wateringAmount(String plantId) {
        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND));
        return Optional.ofNullable(profile.getRecommendedWateringMl())
                .orElseThrow(() -> new BusinessException(ErrorCode.WATERING_AMOUNT_NOT_CONFIGURED));
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    /** 날짜 경계는 서비스 타임존 기준이다. UTC 자정으로 끊으면 하루가 9시간 밀린다. */
    private LocalDateTime startOfDayUtc(LocalDate date) {
        return date.atStartOfDay().minusSeconds(sensorQueryProperties.zoneOffsetSeconds());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
