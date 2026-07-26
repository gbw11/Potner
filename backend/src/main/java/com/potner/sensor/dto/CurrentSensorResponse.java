package com.potner.sensor.dto;

import java.util.List;

public record CurrentSensorResponse(
        String plantId,
        List<CurrentSensorItem> sensors
) {
    public CurrentSensorResponse {
        sensors = List.copyOf(sensors);
    }
}
