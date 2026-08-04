package com.potner.command.application;

import com.potner.command.config.DriveProperties;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.domain.DriveDirection;
import com.potner.command.dto.DriveRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * 방향 버튼으로 로봇을 직접 미는 조작이다.
 *
 * <p>목적지가 정해진 이동({@link DeviceCommandService} 의 {@code NAVIGATE})과는 다른 통로다.
 * 그쪽은 지도 좌표가 입력되어 있어야 하고, 좌표가 비어 있으면 로봇을 한 발짝도 움직일 수 없다.
 * SLAM 지도를 아직 못 만든 자리에서도 바퀴가 도는지 확인해야 하므로 좌표를 보지 않는 통로가
 * 필요하다.
 *
 * <p><strong>{@code device_command} 를 거치지 않는다.</strong> 그 흐름은 같은 종류의 명령이
 * 회신 대기 중이면 409({@code DEVICE_COMMAND_ALREADY_PENDING})로 막는다. 방향 버튼은 연달아
 * 눌리는 것이 정상이라 두 번째 누름부터 전부 막힌다. 게다가 회신을 대조해 확정할 대상도 없다 —
 * 한 번 누름에 대한 {@code result} 계약이 없다. 그래서 {@link com.potner.happiness.application.ManualExpressionService}
 * 처럼 발행만 하고 이력을 남기지 않는다. 발행 로그가 유일한 흔적이다.
 *
 * <p>속도는 요청이 아니라 {@code potner.drive.*} 가 정하고, 로봇은 {@code durationMs} 가 지나면
 * 스스로 멈춘다. 앱이 죽거나 와이파이가 끊겨 {@code STOP} 이 못 나가는 경우를 서버가 막을
 * 방법이 없으므로, 이 시간 제한이 유일한 안전장치다.
 */
@Service
@Transactional(readOnly = true)
public class ManualDriveService {

    private static final Logger log = LoggerFactory.getLogger(ManualDriveService.class);

    private final PlantRepository plantRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final IotDeviceRepository iotDeviceRepository;
    private final RobotCommandPublisher commandPublisher;
    private final DriveProperties properties;

    public ManualDriveService(
            PlantRepository plantRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            IotDeviceRepository iotDeviceRepository,
            RobotCommandPublisher commandPublisher,
            DriveProperties properties
    ) {
        this.plantRepository = plantRepository;
        this.assignmentRepository = assignmentRepository;
        this.iotDeviceRepository = iotDeviceRepository;
        this.commandPublisher = commandPublisher;
        this.properties = properties;
    }

    public void drive(String userId, String plantId, DriveRequest request) {
        requireOwnedPlant(userId, plantId);

        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND));

        // 바퀴가 달린 장치만 주행 명령을 받는다. 어느 종류인지는 NAVIGATE 가 이미 정해 두었으므로
        // 그 값을 그대로 쓴다 - 여기에 JETSON_ORIN 을 또 박으면 하드웨어 구성이 바뀔 때 한쪽만
        // 고쳐져 이동과 수동 주행이 서로 다른 장치로 나간다.
        IotDevice wheels = iotDeviceRepository
                .findAllByRobotIdOrderByDeviceTypeAsc(assignment.getRobotId())
                .stream()
                .filter(candidate ->
                        candidate.getDeviceType() == DeviceCommandType.NAVIGATE.targetDeviceType())
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_DEVICE_NOT_FOUND));

        DriveDirection direction = request.direction();
        DriveCommandPayload payload = new DriveCommandPayload(
                direction.name(),
                scaled(properties.linearMps(), direction.linearSign()),
                scaled(properties.angularRps(), direction.angularSign()),
                durationMs(request),
                UUID.randomUUID().toString()
        );

        commandPublisher.publish(wheels.getDeviceUid(), RobotCommand.drive(payload));
        log.info(
                "Manual drive published: plantId={}, deviceUid={}, direction={}, durationMs={}",
                plantId,
                wheels.getDeviceUid(),
                direction,
                payload.durationMs()
        );
    }

    /**
     * 정지는 시간을 갖지 않는다.
     *
     * <p>버튼에서 손을 뗀 순간 세우는 것이 목적이므로 남은 시간을 기다릴 이유가 없고, 요청이
     * 실수로 시간을 실어 보내도 그 값을 따르면 안 된다.
     */
    private int durationMs(DriveRequest request) {
        if (request.direction().isStop()) {
            return 0;
        }
        return Optional.ofNullable(request.durationMs()).orElse(properties.stepMillis());
    }

    private BigDecimal scaled(BigDecimal magnitude, int sign) {
        return magnitude.multiply(BigDecimal.valueOf(sign));
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
