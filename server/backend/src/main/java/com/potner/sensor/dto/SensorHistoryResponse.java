package com.potner.sensor.dto;

import com.potner.sensor.domain.SensorHistoryInterval;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;

import java.time.LocalDateTime;
import java.util.List;

public record SensorHistoryResponse(
        String plantId,
        SensorType sensorType,
        SensorUnit unit,
        SensorHistoryInterval interval,
        LocalDateTime from,
        LocalDateTime to,
        List<SensorHistoryPoint> points
) {
    public SensorHistoryResponse {
        points = List.copyOf(points);
    }
}
