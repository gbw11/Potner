package com.potner.sensor.dto;

import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 센서 한 종류의 최신 측정값과 판정 결과다.
 * 측정값이 없어도 항목 자체는 반환하므로 앱은 센서 카드 구성을 고정할 수 있다.
 */
public record CurrentSensorItem(
        SensorType sensorType,
        SensorUnit unit,
        BigDecimal value,
        LocalDateTime measuredAt,
        SensorStatus status,
        BigDecimal thresholdMin,
        BigDecimal thresholdMax
) {
}
