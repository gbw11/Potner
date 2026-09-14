package com.potner.mqtt.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "potner.mqtt")
public record MqttProperties(
        boolean enabled,
        @NotBlank String brokerUrl,
        String username,
        String password,
        @NotBlank String clientId,
        @NotBlank String topic,
        @Min(0) @Max(2) int qos,
        @Positive int connectionTimeoutSeconds,
        @Positive int keepAliveSeconds,
        @Positive @Max(Integer.MAX_VALUE) long recoveryIntervalMs
) {

    @AssertTrue(message = "potner.mqtt.username and potner.mqtt.password are required when MQTT is enabled")
    public boolean isCredentialConfigurationValid() {
        return !enabled || hasText(username) && hasText(password);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "MqttProperties[enabled=" + enabled
                + ", brokerUrl=" + brokerUrl
                + ", clientId=" + clientId
                + ", topic=" + topic
                + ", qos=" + qos
                + ", connectionTimeoutSeconds=" + connectionTimeoutSeconds
                + ", keepAliveSeconds=" + keepAliveSeconds
                + ", recoveryIntervalMs=" + recoveryIntervalMs
                + "]";
    }
}
