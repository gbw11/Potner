package com.potner.mqtt.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "potner.mqtt.heartbeat")
public record MqttHeartbeatProperties(
        @NotBlank String topic,
        @Positive long offlineTimeoutSeconds,
        @Positive long offlineCheckIntervalSeconds
) {
}
