package com.potner.mqtt.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
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
        assertThat(message.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(message.value()).isEqualByComparingTo("24.3");
        assertThat(message.unit()).isEqualTo(SensorUnit.CELSIUS);
        assertThat(message.measuredAt()).isEqualTo(OffsetDateTime.parse("2026-07-22T17:10:00+09:00"));
        assertThat(validator.validate(message)).isEmpty();
    }

    @Test
    void rejectsMissingAndOutOfRangeFields() {
        SensorTelemetryMessage message = new SensorTelemetryMessage(
                null,
                " ",
                null,
                null,
                null,
                null
        );

        assertThat(validator.validate(message))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "messageId",
                        "deviceId",
                        "sensorType",
                        "value",
                        "unit",
                        "measuredAt"
                );
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
