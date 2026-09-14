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
        @Positive int timeoutCheckIntervalSeconds,
        @Positive int eventMaxAgeSeconds,
        @Positive int futureEventSkewSeconds
) {
    public ArrivalProperties {
        if (totalTimeoutSeconds <= welcomeWaitSeconds) {
            throw new IllegalArgumentException(
                    "totalTimeoutSeconds는 welcomeWaitSeconds보다 커야 합니다."
            );
        }
        if (commandTimeoutSeconds > totalTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "commandTimeoutSeconds는 totalTimeoutSeconds 이하여야 합니다."
            );
        }
        if (timeoutCheckIntervalSeconds > commandTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "timeoutCheckIntervalSeconds는 commandTimeoutSeconds 이하여야 합니다."
            );
        }
    }
}
