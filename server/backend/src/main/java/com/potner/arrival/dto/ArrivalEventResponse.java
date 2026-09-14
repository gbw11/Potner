package com.potner.arrival.dto;

import java.util.UUID;

public record ArrivalEventResponse(
        UUID eventId,
        String status
) {
    public static ArrivalEventResponse published(UUID eventId) {
        return new ArrivalEventResponse(eventId, "COMMAND_PUBLISHED");
    }

    public static ArrivalEventResponse duplicate(UUID eventId) {
        return new ArrivalEventResponse(eventId, "DUPLICATE_IGNORED");
    }
}
