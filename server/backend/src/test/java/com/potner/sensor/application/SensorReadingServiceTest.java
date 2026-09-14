package com.potner.sensor.application;

import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SensorReadingServiceTest {

    private static final UUID MESSAGE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String DEVICE_ID = "30000000-0000-0000-0000-000000000001";
    private static final String ROBOT_ID = "40000000-0000-0000-0000-000000000001";
    private static final String PLANT_ID = "50000000-0000-0000-0000-000000000001";
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-22T08:15:00Z");
    private static final LocalDateTime MEASURED_AT_UTC =
            LocalDateTime.parse("2026-07-22T08:10:00");
    private static final LocalDateTime RECEIVED_AT_UTC =
            LocalDateTime.parse("2026-07-22T08:15:00");

    private SensorReadingRepository sensorReadingRepository;
    private IotDeviceRepository iotDeviceRepository;
    private PlantDeviceAssignmentRepository assignmentRepository;
    private SensorReadingService service;
    private IotDevice device;
    private Robot robot;
    private PlantDeviceAssignment assignment;

    @BeforeEach
    void setUp() {
        sensorReadingRepository = mock(SensorReadingRepository.class);
        iotDeviceRepository = mock(IotDeviceRepository.class);
        assignmentRepository = mock(PlantDeviceAssignmentRepository.class);
        device = mock(IotDevice.class);
        robot = mock(Robot.class);
        assignment = mock(PlantDeviceAssignment.class);

        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getRobot()).thenReturn(robot);
        when(robot.getId()).thenReturn(ROBOT_ID);
        when(assignment.getPlantId()).thenReturn(PLANT_ID);
        when(iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull("raspberry-01")).thenReturn(Optional.of(device));
        when(assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.of(assignment));

        service = new SensorReadingService(
                sensorReadingRepository,
                iotDeviceRepository,
                assignmentRepository,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void savesOneReadingWithDeviceRobotPlantAndUtcTimestamps() {
        when(sensorReadingRepository.insertReadingIgnoringDuplicate(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), any()
        )).thenReturn(1);

        assertThat(service.save(message())).isEqualTo(SensorReadingSaveResult.SAVED);

        verify(sensorReadingRepository).insertReadingIgnoringDuplicate(
                PLANT_ID,
                ROBOT_ID,
                DEVICE_ID,
                MESSAGE_ID.toString(),
                "TEMPERATURE",
                new BigDecimal("24.3"),
                "CELSIUS",
                MEASURED_AT_UTC,
                RECEIVED_AT_UTC
        );
    }

    @Test
    void returnsDuplicateWhenSingleInsertAffectsZeroRows() {
        when(sensorReadingRepository.insertReadingIgnoringDuplicate(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), any()
        )).thenReturn(0);

        assertThat(service.save(message())).isEqualTo(SensorReadingSaveResult.DUPLICATE);
    }

    @Test
    void returnsDeviceNotFoundWithoutSaving() {
        when(iotDeviceRepository.findByDeviceUidAndReleasedAtIsNull("raspberry-01")).thenReturn(Optional.empty());

        assertThat(service.save(message())).isEqualTo(SensorReadingSaveResult.DEVICE_NOT_FOUND);

        verify(assignmentRepository, never())
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(anyString());
        verify(sensorReadingRepository, never()).insertReadingIgnoringDuplicate(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), any()
        );
    }

    @Test
    void returnsRobotNotFoundWhenDeviceHasNoRobot() {
        when(device.getRobot()).thenReturn(null);

        assertThat(service.save(message())).isEqualTo(SensorReadingSaveResult.ROBOT_NOT_FOUND);

        verify(assignmentRepository, never())
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(anyString());
    }

    @Test
    void returnsAssignmentNotFoundWithoutSaving() {
        when(assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.empty());

        assertThat(service.save(message()))
                .isEqualTo(SensorReadingSaveResult.PLANT_ASSIGNMENT_NOT_FOUND);

        verify(sensorReadingRepository, never()).insertReadingIgnoringDuplicate(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), any()
        );
    }

    @Test
    void rejectsUnexpectedAffectedRows() {
        when(sensorReadingRepository.insertReadingIgnoringDuplicate(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), any()
        )).thenReturn(2);

        assertThatThrownBy(() -> service.save(message()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MESSAGE_ID.toString())
                .hasMessageContaining("affectedRows=2");
    }

    private SensorTelemetryMessage message() {
        return new SensorTelemetryMessage(
                MESSAGE_ID,
                "raspberry-01",
                SensorType.TEMPERATURE,
                new BigDecimal("24.3"),
                SensorUnit.CELSIUS,
                OffsetDateTime.parse("2026-07-22T17:10:00+09:00")
        );
    }
}
