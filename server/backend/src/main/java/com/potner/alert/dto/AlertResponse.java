package com.potner.alert.dto;

import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 알림 한 건이다.
 *
 * <p>사용자에게 보여줄 문구는 담지 않는다. 서버가 문구를 만들면 앱의 표현 변경이 서버 배포에 묶인다.
 * 앱은 {@code plantName}, {@code metricType}, {@code deviation}으로 문구를 조립한다.
 */
public record AlertResponse(
        String alertId,
        String plantId,
        String plantName,
        AlertMetricType metricType,
        AlertDeviation deviation,
        BigDecimal measuredValue,
        BigDecimal thresholdMin,
        BigDecimal thresholdMax,
        LocalDateTime occurredAt,
        LocalDateTime resolvedAt,
        boolean active,
        boolean read,
        LocalDateTime createdAt
) {
    public static AlertResponse of(Alert alert, String plantName) {
        return new AlertResponse(
                alert.getId(),
                alert.getPlantId(),
                plantName,
                alert.getMetricType(),
                alert.getDeviation(),
                alert.getMeasuredValue(),
                alert.getThresholdMin(),
                alert.getThresholdMax(),
                alert.getOccurredAt(),
                alert.getResolvedAt(),
                alert.isActive(),
                alert.isRead(),
                alert.getCreatedAt()
        );
    }
}
