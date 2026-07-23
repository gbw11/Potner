package com.potner.sensor.application;

import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.domain.SensorReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class SensorReadingService {

    private static final int SENSOR_COUNT_PER_MESSAGE = 4;

    private final SensorReadingRepository sensorReadingRepository;
    private final RobotRepository robotRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final Clock clock;

    public SensorReadingService(
            SensorReadingRepository sensorReadingRepository,
            RobotRepository robotRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            Clock clock
    ) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.robotRepository = robotRepository;
        this.assignmentRepository = assignmentRepository;
        this.clock = clock;
    }

    @Transactional
    public SensorReadingSaveResult save(SensorTelemetryMessage message) {
        Robot robot = robotRepository.findByDeviceUid(message.deviceId()).orElse(null);
        if (robot == null) {
            return SensorReadingSaveResult.ROBOT_NOT_FOUND;
        }

        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robot.getId())
                .orElse(null);
        if (assignment == null) {
            return SensorReadingSaveResult.PLANT_ASSIGNMENT_NOT_FOUND;
        }

        String deviceMessageId = message.messageId().toString();
        LocalDateTime measuredAt = LocalDateTime.ofInstant(
                message.measuredAt().toInstant(),
                ZoneOffset.UTC
        );
        LocalDateTime receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int insertedRows = sensorReadingRepository.insertTelemetryReadingsIgnoringDuplicate(
                assignment.getPlantId(),
                robot.getId(),
                deviceMessageId,
                message.temperatureC(),
                message.humidityPct(),
                message.soilMoisturePct(),
                message.illuminanceLux(),
                measuredAt,
                receivedAt
        );
        if (insertedRows == SENSOR_COUNT_PER_MESSAGE) {
            return SensorReadingSaveResult.SAVED;
        }
        if (insertedRows == 0) {
            return SensorReadingSaveResult.DUPLICATE;
        }
        throw new IllegalStateException(
                "Sensor readings were only partially inserted: messageId="
                        + deviceMessageId
                        + ", affectedRows="
                        + insertedRows
        );
    }
}
