package com.potner.sensor.application;

import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.domain.SensorReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private static final String ROBOT_ID = "40000000-0000-0000-0000-000000000001";
    private static final String PLANT_ID = "50000000-0000-0000-0000-000000000001";
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-22T08:15:00Z");
    private static final LocalDateTime MEASURED_AT_UTC =
            LocalDateTime.parse("2026-07-22T08:10:00");
    private static final LocalDateTime RECEIVED_AT_UTC =
            LocalDateTime.parse("2026-07-22T08:15:00");

    private SensorReadingRepository sensorReadingRepository;
    private RobotRepository robotRepository;
    private PlantDeviceAssignmentRepository assignmentRepository;
    private SensorReadingService service;
    private Robot robot;
    private PlantDeviceAssignment assignment;

    @BeforeEach
    void setUp() {
        sensorReadingRepository = mock(SensorReadingRepository.class);
        robotRepository = mock(RobotRepository.class);
        assignmentRepository = mock(PlantDeviceAssignmentRepository.class);
        robot = mock(Robot.class);
        assignment = mock(PlantDeviceAssignment.class);

        when(robot.getId()).thenReturn(ROBOT_ID);
        when(assignment.getPlantId()).thenReturn(PLANT_ID);
        when(robotRepository.findByDeviceUid("raspberry-01")).thenReturn(Optional.of(robot));
        when(assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.of(assignment));

        service = new SensorReadingService(
                sensorReadingRepository,
                robotRepository,
                assignmentRepository,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void savesFourMappedReadingsWithUtcTimestamps() {
        when(sensorReadingRepository.insertTelemetryReadingsIgnoringDuplicate(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(4);

        assertThat(service.save(message())).isEqualTo(SensorReadingSaveResult.SAVED);

        verify(sensorReadingRepository).insertTelemetryReadingsIgnoringDuplicate(
                PLANT_ID,
                ROBOT_ID,
                MESSAGE_ID.toString(),
                new BigDecimal("24.3"),
                new BigDecimal("58.1"),
                new BigDecimal("42.7"),
                new BigDecimal("1350"),
                MEASURED_AT_UTC,
                RECEIVED_AT_UTC
        );
    }

    @Test
    void returnsDuplicateWhenMultiRowInsertAffectsZeroRows() {
        when(sensorReadingRepository.insertTelemetryReadingsIgnoringDuplicate(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(0);

        assertThat(service.save(message())).isEqualTo(SensorReadingSaveResult.DUPLICATE);

        verify(sensorReadingRepository).insertTelemetryReadingsIgnoringDuplicate(
                PLANT_ID,
                ROBOT_ID,
                MESSAGE_ID.toString(),
                new BigDecimal("24.3"),
                new BigDecimal("58.1"),
                new BigDecimal("42.7"),
                new BigDecimal("1350"),
                MEASURED_AT_UTC,
                RECEIVED_AT_UTC
        );
    }

    @Test
    void returnsRobotNotFoundWithoutSaving() {
        when(robotRepository.findByDeviceUid("raspberry-01")).thenReturn(Optional.empty());

        assertThat(service.save(message())).isEqualTo(SensorReadingSaveResult.ROBOT_NOT_FOUND);

        verify(assignmentRepository, never())
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(anyString());
        verify(sensorReadingRepository, never()).insertTelemetryReadingsIgnoringDuplicate(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void returnsAssignmentNotFoundWithoutSaving() {
        when(assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.empty());

        assertThat(service.save(message()))
                .isEqualTo(SensorReadingSaveResult.PLANT_ASSIGNMENT_NOT_FOUND);

        verify(sensorReadingRepository, never()).insertTelemetryReadingsIgnoringDuplicate(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void rejectsPartialMultiRowInsertSoTransactionCanRollBack(int affectedRows) {
        when(sensorReadingRepository.insertTelemetryReadingsIgnoringDuplicate(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(affectedRows);

        assertThatThrownBy(() -> service.save(message()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MESSAGE_ID.toString())
                .hasMessageContaining("affectedRows=" + affectedRows);
    }

    private SensorTelemetryMessage message() {
        return new SensorTelemetryMessage(
                MESSAGE_ID,
                "raspberry-01",
                new BigDecimal("24.3"),
                new BigDecimal("58.1"),
                new BigDecimal("42.7"),
                new BigDecimal("1350"),
                OffsetDateTime.parse("2026-07-22T17:10:00+09:00")
        );
    }
}
