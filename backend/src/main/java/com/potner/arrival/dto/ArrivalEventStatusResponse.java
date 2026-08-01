package com.potner.arrival.dto;

import com.potner.arrival.domain.ArrivalEvent;
import com.potner.arrival.domain.ArrivalEventType;
import com.potner.arrival.domain.ArrivalProcessingStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ArrivalEventStatusResponse(
        UUID eventId,
        UUID visitId,
        ArrivalEventType eventType,
        ArrivalProcessingStatus status,
        String errorMessage,
        LocalDateTime reportedAt
) {
    public static ArrivalEventStatusResponse from(ArrivalEvent event) {
        return new ArrivalEventStatusResponse(
                UUID.fromString(event.getEventId()),
                UUID.fromString(event.getVisitId()),
                event.getEventType(),
                event.getProcessingStatus(),
                event.getErrorMessage(),
                event.getReportedAt()
        );
    }
}
