package com.potner.arrival.application;

import com.potner.arrival.domain.ArrivalEventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ArrivalCommandIssuedEvent(
        String eventId,
        String visitId,
        String deviceUid,
        ArrivalEventType eventType,
        BigDecimal greetingX,
        BigDecimal greetingY,
        BigDecimal greetingYaw,
        BigDecimal homeX,
        BigDecimal homeY,
        BigDecimal homeYaw,
        int welcomeWaitSeconds,
        int totalTimeoutSeconds,
        OffsetDateTime publishedAt
) {
}
