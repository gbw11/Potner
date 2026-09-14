package com.potner.mqtt.dto;

import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SensorTelemetryMessage(
        @NotNull UUID messageId,
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}\\s]+$")
        String deviceId,
        @NotNull SensorType sensorType,
        @NotNull BigDecimal value,
        @NotNull SensorUnit unit,
        @NotNull OffsetDateTime measuredAt
) {
}
