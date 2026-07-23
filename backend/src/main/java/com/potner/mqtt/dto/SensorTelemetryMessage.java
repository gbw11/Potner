package com.potner.mqtt.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
        @NotNull @DecimalMin("-40") @DecimalMax("85") BigDecimal temperatureC,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal humidityPct,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal soilMoisturePct,
        @NotNull @DecimalMin("0") BigDecimal illuminanceLux,
        @NotNull OffsetDateTime measuredAt
) {
}
