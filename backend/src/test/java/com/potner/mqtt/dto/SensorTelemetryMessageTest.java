package com.potner.mqtt.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SensorTelemetryMessageTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void deserializesUuidNumbersAndOffsetDateTime() throws Exception {
        SensorTelemetryMessage message = objectMapper.readValue(validJson(), SensorTelemetryMessage.class);

        assertThat(message.messageId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(message.temperatureC()).isEqualByComparingTo("24.3");
        assertThat(message.measuredAt()).isEqualTo(OffsetDateTime.parse("2026-07-22T17:10:00+09:00"));
        assertThat(validator.validate(message)).isEmpty();
    }

    @Test
    void rejectsMissingAndOutOfRangeFields() {
        SensorTelemetryMessage message = new SensorTelemetryMessage(
                null,
                " ",
                new BigDecimal("85.01"),
                new BigDecimal("100.01"),
                new BigDecimal("-0.01"),
                new BigDecimal("-1"),
                null
        );

        assertThat(validator.validate(message))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "messageId",
                        "deviceId",
                        "temperatureC",
                        "humidityPct",
                        "soilMoisturePct",
                        "illuminanceLux",
                        "measuredAt"
                );
    }

    private String validJson() {
        return """
                {
                  "messageId":"550e8400-e29b-41d4-a716-446655440000",
                  "deviceId":"raspberry-01",
                  "temperatureC":24.3,
                  "humidityPct":58.1,
                  "soilMoisturePct":42.7,
                  "illuminanceLux":1350,
                  "measuredAt":"2026-07-22T17:10:00+09:00"
                }
                """;
    }
}
