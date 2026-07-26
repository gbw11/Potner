package com.potner.light.dto;

import java.util.List;

public record DailyLightResponse(
        String plantId,
        String zoneOffset,
        DailyLightTodayResponse today,
        List<DailyLightDayResponse> history
) {
    public DailyLightResponse {
        history = List.copyOf(history);
    }
}
