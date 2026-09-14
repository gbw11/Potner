package com.potner.sensor.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 집계 구간 하나다. {@code bucketAt}은 구간 시작 시각이며 UTC로 반환한다.
 */
public record SensorHistoryPoint(
        LocalDateTime bucketAt,
        BigDecimal averageValue,
        BigDecimal minimumValue,
        BigDecimal maximumValue,
        long sampleCount
) {
}
