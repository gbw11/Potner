package com.potner.alert.dto;

import java.util.List;

public record AlertListResponse(
        List<AlertResponse> alerts,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long unreadCount
) {
    public AlertListResponse {
        alerts = List.copyOf(alerts);
    }
}
