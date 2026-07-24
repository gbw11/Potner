package com.potner.mqtt.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatMessageTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsValidHeartbeatContract() {
        HeartbeatMessage message = new HeartbeatMessage(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "raspberry-01",
                OffsetDateTime.parse("2026-07-23T05:30:00Z")
        );

        assertThat(validator.validate(message)).isEmpty();
    }

    @Test
    void rejectsMissingFieldsBlankDeviceIdAndOverlongDeviceId() {
        HeartbeatMessage missingFields = new HeartbeatMessage(null, " ", null);
        HeartbeatMessage overlongDeviceId = new HeartbeatMessage(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "d".repeat(101),
                OffsetDateTime.parse("2026-07-23T05:30:00Z")
        );

        assertThat(validator.validate(missingFields))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("messageId", "deviceId", "sentAt");
        assertThat(validator.validate(overlongDeviceId))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("deviceId");
    }
}
