package com.potner.arrival.dto;

import com.potner.arrival.domain.ArrivalEventSource;
import com.potner.arrival.domain.ArrivalEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ArrivalEventRequest(
        @NotNull(message = "eventId는 필수입니다.") UUID eventId,
        @NotNull(message = "visitId는 필수입니다.") UUID visitId,
        @NotNull(message = "eventType은 필수입니다.") ArrivalEventType eventType,
        @NotNull(message = "source는 필수입니다.") ArrivalEventSource source,
        @Size(max = 50, message = "geofenceId는 50자 이하여야 합니다.") String geofenceId,
        @NotNull(message = "occurredAt은 필수입니다.") OffsetDateTime occurredAt
) {
}
