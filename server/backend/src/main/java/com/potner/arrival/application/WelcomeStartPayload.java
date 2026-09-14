package com.potner.arrival.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WelcomeStartPayload(
        String eventId,
        String visitId,
        String requestId,
        String destination,
        BigDecimal x,
        BigDecimal y,
        BigDecimal yaw,
        String returnDestination,
        BigDecimal returnX,
        BigDecimal returnY,
        BigDecimal returnYaw,
        int waitSeconds,
        int totalTimeoutSeconds,
        OffsetDateTime publishedAt
) {
}
