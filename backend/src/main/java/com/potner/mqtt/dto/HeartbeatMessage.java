package com.potner.mqtt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HeartbeatMessage(
        @NotNull UUID messageId,
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}\\s]+$")
        String deviceId,
        @NotNull OffsetDateTime sentAt
) {
}
