package com.potner.mqtt.application;

import com.potner.alert.application.AlertEvaluationResult;
import com.potner.alert.application.AlertEvaluationService;
import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.application.SensorReadingSaveResult;
import com.potner.sensor.application.SensorReadingService;
import com.potner.sensor.domain.SensorType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SensorTelemetryMessageProcessorTest {

    private static final String TOPIC = "potner/device/raspberry-01/sensor/telemetry";
    private static final Instant NOW = Instant.parse("2026-07-22T08:15:00Z");

    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";

    private SensorReadingService sensorReadingService;
    private AlertEvaluationService alertEvaluationService;
    private SensorTelemetryMessageProcessor processor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        sensorReadingService = mock(SensorReadingService.class);
        alertEvaluationService = mock(AlertEvaluationService.class);
        processor = new SensorTelemetryMessageProcessor(
                objectMapper,
                validator,
                new SensorTelemetryTopicParser(),
                new SensorTelemetryValueValidator(),
                sensorReadingService,
                alertEvaluationService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void savesValidTelemetry() {
        when(sensorReadingService.save(any())).thenReturn(SensorReadingSaveResult.SAVED);

        processor.process(TOPIC, validJson());

        verify(sensorReadingService).save(any(SensorTelemetryMessage.class));
    }

    @Test
    void savedTelemetryTriggersAlertEvaluation() {
        when(sensorReadingService.save(any())).thenReturn(SensorReadingSaveResult.SAVED);
        when(sensorReadingService.findAssignedPlantId("raspberry-01"))
                .thenReturn(java.util.Optional.of(PLANT_ID));
        when(alertEvaluationService.evaluate(PLANT_ID, SensorType.TEMPERATURE))
                .thenReturn(AlertEvaluationResult.CREATED);

        processor.process(TOPIC, validJson());

        verify(alertEvaluationService).evaluate(PLANT_ID, SensorType.TEMPERATURE);
    }

    @Test
    void duplicateTelemetryDoesNotTriggerAlertEvaluation() {
        when(sensorReadingService.save(any())).thenReturn(SensorReadingSaveResult.DUPLICATE);

        processor.process(TOPIC, validJson());

        verify(alertEvaluationService, never()).evaluate(any(), any());
    }

    @Test
    void rejectsMalformedJsonAndInvalidValues() {
        processor.process(TOPIC, "{invalid-json");
        processor.process(TOPIC, validJson().replace("24.3", "120.0"));

        verify(sensorReadingService, never()).save(any());
    }

    @Test
    void processesNextValidMessageAfterMalformedJson() {
        when(sensorReadingService.save(any())).thenReturn(SensorReadingSaveResult.SAVED);

        processor.process(TOPIC, "{invalid-json");
        processor.process(TOPIC, validJson());

        verify(sensorReadingService).save(any(SensorTelemetryMessage.class));
    }

    @Test
    void missingDeviceRobotAndAssignmentDoNotInterruptProcessing() {
        when(sensorReadingService.save(any()))
                .thenReturn(
                        SensorReadingSaveResult.DEVICE_NOT_FOUND,
                        SensorReadingSaveResult.ROBOT_NOT_FOUND,
                        SensorReadingSaveResult.PLANT_ASSIGNMENT_NOT_FOUND
                );

        processor.process(TOPIC, validJson());
        processor.process(TOPIC, validJson());
        processor.process(TOPIC, validJson());

        verify(sensorReadingService, times(3)).save(any(SensorTelemetryMessage.class));
    }

    @Test
    void rejectsInvalidTopicAndDeviceIdMismatch() {
        processor.process("potner/device/raspberry-01/sensor/telemetry/extra", validJson());
        processor.process(
                TOPIC,
                validJson().replace("\"raspberry-01\"", "\"raspberry-02\"")
        );

        verify(sensorReadingService, never()).save(any());
    }

    @Test
    void rejectsMeasuredAtMoreThanTenMinutesInFuture() {
        processor.process(
                TOPIC,
                validJson().replace("2026-07-22T17:10:00+09:00", "2026-07-22T17:25:00+09:00")
        );

        verify(sensorReadingService, never()).save(any());
    }

    @Test
    void rejectsUnknownSensorTypeInvalidUnitAndOutOfRangeValue() {
        processor.process(TOPIC, validJson().replace("TEMPERATURE", "UNKNOWN"));
        processor.process(TOPIC, validJson().replace("CELSIUS", "PERCENT"));
        processor.process(TOPIC, validJson().replace("24.3", "85.1"));

        verify(sensorReadingService, never()).save(any());
    }

    private String validJson() {
        return """
                {
                  "messageId":"550e8400-e29b-41d4-a716-446655440000",
                  "deviceId":"raspberry-01",
                  "sensorType":"TEMPERATURE",
                  "value":24.3,
                  "unit":"CELSIUS",
                  "measuredAt":"2026-07-22T17:10:00+09:00"
                }
                """;
    }
}
