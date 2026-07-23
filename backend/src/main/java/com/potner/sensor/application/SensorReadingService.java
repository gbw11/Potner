package com.potner.sensor.application;

import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

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
        if (sensorReadingRepository.existsByDeviceMessageId(deviceMessageId)) {
            return SensorReadingSaveResult.DUPLICATE;
        }

        LocalDateTime measuredAt = LocalDateTime.ofInstant(
                message.measuredAt().toInstant(),
                ZoneOffset.UTC
        );
        LocalDateTime receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<SensorReading> readings = List.of(
                SensorReading.create(
                        assignment.getPlantId(),
                        robot.getId(),
                        deviceMessageId,
                        SensorType.TEMPERATURE,
                        message.temperatureC(),
                        SensorUnit.CELSIUS,
                        measuredAt,
                        receivedAt
                ),
                SensorReading.create(
                        assignment.getPlantId(),
                        robot.getId(),
                        deviceMessageId,
                        SensorType.HUMIDITY,
                        message.humidityPct(),
                        SensorUnit.PERCENT,
                        measuredAt,
                        receivedAt
                ),
                SensorReading.create(
                        assignment.getPlantId(),
                        robot.getId(),
                        deviceMessageId,
                        SensorType.SOIL_MOISTURE,
                        message.soilMoisturePct(),
                        SensorUnit.PERCENT,
                        measuredAt,
                        receivedAt
                ),
                SensorReading.create(
                        assignment.getPlantId(),
                        robot.getId(),
                        deviceMessageId,
                        SensorType.ILLUMINANCE,
                        message.illuminanceLux(),
                        SensorUnit.LUX,
                        measuredAt,
                        receivedAt
                )
        );

        int insertedRows = readings.stream()
                .mapToInt(this::insertIgnoringDuplicate)
                .sum();
        if (insertedRows == SENSOR_COUNT_PER_MESSAGE) {
            return SensorReadingSaveResult.SAVED;
        }
        if (insertedRows == 0
                && sensorReadingRepository.countByDeviceMessageId(deviceMessageId)
                == SENSOR_COUNT_PER_MESSAGE) {
            return SensorReadingSaveResult.DUPLICATE;
        }
        throw new IllegalStateException(
                "Sensor readings were only partially inserted: messageId=" + deviceMessageId
        );
    }

    private int insertIgnoringDuplicate(SensorReading reading) {
        return sensorReadingRepository.insertIgnoringDuplicate(
                reading.getPlantId(),
                reading.getRobotId(),
                reading.getDeviceMessageId(),
                reading.getSensorType().name(),
                reading.getMeasuredValue(),
                reading.getUnit().name(),
                SensorQuality.GOOD.name(),
                reading.getMeasuredAt(),
                reading.getReceivedAt()
        );
    }
}
