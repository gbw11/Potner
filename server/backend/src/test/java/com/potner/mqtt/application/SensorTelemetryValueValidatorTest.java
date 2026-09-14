package com.potner.mqtt.application;

import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SensorTelemetryValueValidatorTest {

    private final SensorTelemetryValueValidator validator = new SensorTelemetryValueValidator();

    @Test
    void acceptsSupportedTypeUnitAndBoundaryValues() {
        assertThat(validator.isValid(message(SensorType.TEMPERATURE, "-40", SensorUnit.CELSIUS)))
                .isTrue();
        assertThat(validator.isValid(message(SensorType.TEMPERATURE, "85", SensorUnit.CELSIUS)))
                .isTrue();
        assertThat(validator.isValid(message(SensorType.HUMIDITY, "100", SensorUnit.PERCENT)))
                .isTrue();
        assertThat(validator.isValid(message(SensorType.SOIL_MOISTURE, "0", SensorUnit.PERCENT)))
                .isTrue();
        assertThat(validator.isValid(message(SensorType.ILLUMINANCE, "0", SensorUnit.LUX)))
                .isTrue();
    }

    @Test
    void rejectsUnsupportedUnitCombinationAndOutOfRangeValues() {
        assertThat(validator.isValid(message(SensorType.TEMPERATURE, "24", SensorUnit.PERCENT)))
                .isFalse();
        assertThat(validator.isValid(message(SensorType.HUMIDITY, "101", SensorUnit.PERCENT)))
                .isFalse();
        assertThat(validator.isValid(message(SensorType.SOIL_MOISTURE, "-0.1", SensorUnit.PERCENT)))
                .isFalse();
        assertThat(validator.isValid(message(SensorType.ILLUMINANCE, "-1", SensorUnit.LUX)))
                .isFalse();
    }

    private SensorTelemetryMessage message(
            SensorType sensorType,
            String value,
            SensorUnit unit
    ) {
        return new SensorTelemetryMessage(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "raspberry-01",
                sensorType,
                new BigDecimal(value),
                unit,
                OffsetDateTime.parse("2026-07-22T17:10:00+09:00")
        );
    }
}
