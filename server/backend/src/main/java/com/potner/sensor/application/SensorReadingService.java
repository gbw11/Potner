package com.potner.sensor.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.domain.SensorReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class SensorReadingService {

    private final SensorReadingRepository sensorReadingRepository;
    private final IotDeviceRepository iotDeviceRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final Clock clock;

    public SensorReadingService(
            SensorReadingRepository sensorReadingRepository,
            IotDeviceRepository iotDeviceRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            Clock clock
    ) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.iotDeviceRepository = iotDeviceRepository;
        this.assignmentRepository = assignmentRepository;
        this.clock = clock;
    }

    @Transactional
    public SensorReadingSaveResult save(SensorTelemetryMessage message) {
        IotDevice device = iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull(message.deviceId()).orElse(null);
        if (device == null) {
            return SensorReadingSaveResult.DEVICE_NOT_FOUND;
        }

        Robot robot = device.getRobot();
        if (robot == null) {
            return SensorReadingSaveResult.ROBOT_NOT_FOUND;
        }

        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robot.getId())
                .orElse(null);
        if (assignment == null) {
            return SensorReadingSaveResult.PLANT_ASSIGNMENT_NOT_FOUND;
        }

        LocalDateTime measuredAt = LocalDateTime.ofInstant(
                message.measuredAt().toInstant(),
                ZoneOffset.UTC
        );
        LocalDateTime receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int insertedRows = sensorReadingRepository.insertReadingIgnoringDuplicate(
                assignment.getPlantId(),
                robot.getId(),
                device.getId(),
                message.messageId().toString(),
                message.sensorType().name(),
                message.value(),
                message.unit().name(),
                measuredAt,
                receivedAt
        );
        if (insertedRows == 1) {
            return SensorReadingSaveResult.SAVED;
        }
        if (insertedRows == 0) {
            return SensorReadingSaveResult.DUPLICATE;
        }
        throw new IllegalStateException(
                "Unexpected sensor reading affected rows: messageId="
                        + message.messageId()
                        + ", affectedRows="
                        + insertedRows
        );
    }

    /**
     * 장치가 현재 담당하는 식물을 찾는다. 이상 판정은 식물 단위로 이루어지므로
     * 측정값 저장 이후 판정을 이어가려면 이 값이 필요하다.
     */
    @Transactional(readOnly = true)
    public Optional<String> findAssignedPlantId(String deviceUid) {
        return iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull(deviceUid)
                .map(IotDevice::getRobot)
                .flatMap(robot -> assignmentRepository
                        .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robot.getId()))
                .map(PlantDeviceAssignment::getPlantId);
    }
}
