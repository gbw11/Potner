package com.potner.arrival.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "potner.arrival")
public record ArrivalProperties(
        @Positive int welcomeWaitSeconds,
        @Positive int totalTimeoutSeconds,
        @Positive int commandTimeoutSeconds,
        @Positive int timeoutCheckIntervalSeconds
) {
}
