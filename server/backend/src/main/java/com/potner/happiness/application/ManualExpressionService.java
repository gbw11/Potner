package com.potner.happiness.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.dto.ExpressionCommandPayload;
import com.potner.happiness.dto.PublishExpressionRequest;
import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 표정을 손으로 한 번 발행한다.
 *
 * <p>시연과 진단용이다. 표정은 {@link ExpressionPublishScheduler} 가 주기적으로 판정해 밀어
 * 주므로 평소에 사람이 개입할 자리가 없다. 그런데 판정 근거(온·습도, 로봇 상태, 개화, 광량)를
 * 시연 자리에서 만들 수 없을 때, 로봇 디스플레이가 죽은 것인지 판정이 그런 것인지 구별할
 * 방법이 없다. 그래서 판정을 건너뛰고 값을 직접 보내는 통로를 둔다.
 *
 * <p><strong>다음 주기 발행이 덮어쓴다.</strong> 오버라이드를 저장하지 않으므로 이 표정은
 * {@code potner.happiness.publish-interval-seconds}(기본 30초) 동안만 유지된다. 잠깐 보여주는
 * 용도로 충분하다는 판단이며, 오래 붙잡아 두려면 주기 발행이 참조하는 오버라이드가 필요하다.
 *
 * <p>이력을 남기지 않는다. {@code device_command} 는 회신을 대조해 수행 여부를 확정하는
 * 테이블인데, 표정에는 회신 계약이 없어서 무엇을 확정할지가 없다. 발행 로그가 유일한 흔적이다.
 */
@Service
@Transactional(readOnly = true)
public class ManualExpressionService {

    private static final Logger log = LoggerFactory.getLogger(ManualExpressionService.class);

    private final PlantRepository plantRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final IotDeviceRepository iotDeviceRepository;
    private final RobotCommandPublisher commandPublisher;
    private final HappinessProperties properties;

    public ManualExpressionService(
            PlantRepository plantRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            IotDeviceRepository iotDeviceRepository,
            RobotCommandPublisher commandPublisher,
            HappinessProperties properties
    ) {
        this.plantRepository = plantRepository;
        this.assignmentRepository = assignmentRepository;
        this.iotDeviceRepository = iotDeviceRepository;
        this.commandPublisher = commandPublisher;
        this.properties = properties;
    }

    public void publish(String userId, String plantId, PublishExpressionRequest request) {
        requireOwnedPlant(userId, plantId);

        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND));

        // 디스플레이가 달린 장치만 표정을 받는다. 어느 종류인지는
        // potner.happiness.display-device-type 이 정한다 - 하드웨어 구성이 바뀌면 그 값만 바꾼다.
        IotDevice display = iotDeviceRepository
                .findAllByRobotIdOrderByDeviceTypeAsc(assignment.getRobotId())
                .stream()
                .filter(candidate -> candidate.getDeviceType() == properties.displayDeviceType())
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_DEVICE_NOT_FOUND));

        ExpressionReason reason = Optional.ofNullable(request.reason())
                .orElse(ExpressionReason.NONE);

        commandPublisher.publish(
                display.getDeviceUid(),
                RobotCommand.expression(new ExpressionCommandPayload(
                        plantId,
                        request.expression(),
                        reason
                ))
        );
        log.info(
                "Manual expression published: plantId={}, deviceUid={}, expression={}, reason={}",
                plantId,
                display.getDeviceUid(),
                request.expression(),
                reason
        );
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
