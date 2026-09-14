package com.potner.arrival.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WelcomeCancelPayload(
        String eventId,
        String visitId,
        String requestId,
        String returnDestination,
        BigDecimal returnX,
        BigDecimal returnY,
        BigDecimal returnYaw,
        OffsetDateTime publishedAt
) {
}
