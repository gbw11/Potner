package com.potner.mqtt.application;

import com.potner.device.application.HeartbeatUpdateResult;
import com.potner.device.application.IotDeviceHeartbeatService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeartbeatMessageProcessorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T05:30:00Z");
    private static final String RASPBERRY_TOPIC =
            "potner/device/raspberry-01/status/heartbeat";
    private static final String JETSON_TOPIC =
            "potner/device/jetson-01/status/heartbeat";

    private IotDeviceHeartbeatService heartbeatService;
    private HeartbeatMessageProcessor processor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        heartbeatService = org.mockito.Mockito.mock(IotDeviceHeartbeatService.class);
        processor = new HeartbeatMessageProcessor(
                objectMapper,
                validator,
                new HeartbeatTopicParser(),
                heartbeatService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void processesRaspberryAndJetsonHeartbeats() {
        when(heartbeatService.recordHeartbeat("raspberry-01"))
                .thenReturn(HeartbeatUpdateResult.UPDATED);
        when(heartbeatService.recordHeartbeat("jetson-01"))
                .thenReturn(HeartbeatUpdateResult.UPDATED);

        processor.process(RASPBERRY_TOPIC, validJson("raspberry-01"));
        processor.process(JETSON_TOPIC, validJson("jetson-01"));

        verify(heartbeatService).recordHeartbeat("raspberry-01");
        verify(heartbeatService).recordHeartbeat("jetson-01");
    }

    @Test
    void ignoresUnknownDeviceWithoutThrowing() {
        when(heartbeatService.recordHeartbeat("raspberry-01"))
                .thenReturn(HeartbeatUpdateResult.DEVICE_NOT_FOUND);

        processor.process(RASPBERRY_TOPIC, validJson("raspberry-01"));

        verify(heartbeatService).recordHeartbeat("raspberry-01");
    }

    @Test
    void rejectsMalformedJsonInvalidUuidAndMissingFields() {
        processor.process(RASPBERRY_TOPIC, "{invalid-json");
        processor.process(
                RASPBERRY_TOPIC,
                validJson("raspberry-01").replace(
                        "550e8400-e29b-41d4-a716-446655440000",
                        "not-a-uuid"
                )
        );
        processor.process(
                RASPBERRY_TOPIC,
                """
                        {
                          "messageId":"550e8400-e29b-41d4-a716-446655440000",
                          "deviceId":"raspberry-01"
                        }
                        """
        );

        verify(heartbeatService, never()).recordHeartbeat("raspberry-01");
    }

    @Test
    void rejectsTopicPayloadMismatchAndFutureSentAt() {
        processor.process(RASPBERRY_TOPIC, validJson("jetson-01"));
        processor.process(
                RASPBERRY_TOPIC,
                validJson("raspberry-01").replace(
                        "2026-07-23T05:25:00Z",
                        "2026-07-23T05:41:00Z"
                )
        );

        verify(heartbeatService, never()).recordHeartbeat("raspberry-01");
        verify(heartbeatService, never()).recordHeartbeat("jetson-01");
    }

    @Test
    void continuesWithNextValidMessageAfterMalformedMessage() {
        when(heartbeatService.recordHeartbeat("raspberry-01"))
                .thenReturn(HeartbeatUpdateResult.UPDATED);

        processor.process(RASPBERRY_TOPIC, "{invalid-json");
        processor.process(RASPBERRY_TOPIC, validJson("raspberry-01"));

        verify(heartbeatService, times(1)).recordHeartbeat("raspberry-01");
    }

    private String validJson(String deviceId) {
        return """
                {
                  "messageId":"550e8400-e29b-41d4-a716-446655440000",
                  "deviceId":"%s",
                  "sentAt":"2026-07-23T05:25:00Z"
                }
                """.formatted(deviceId);
    }
}
