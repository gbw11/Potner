package com.potner.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "alert")
public class Alert {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "alert_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", length = 30, nullable = false)
    private AlertMetricType metricType;

    @Enumerated(EnumType.STRING)
    @Column(name = "deviation", length = 10, nullable = false)
    private AlertDeviation deviation;

    @Column(name = "measured_value", precision = 14, scale = 4, nullable = false)
    private BigDecimal measuredValue;

    @Column(name = "threshold_min", precision = 14, scale = 4, nullable = false)
    private BigDecimal thresholdMin;

    @Column(name = "threshold_max", precision = 14, scale = 4, nullable = false)
    private BigDecimal thresholdMax;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected Alert() {
    }

    private Alert(
            String userId,
            String plantId,
            AlertMetricType metricType,
            AlertDeviation deviation,
            BigDecimal measuredValue,
            BigDecimal thresholdMin,
            BigDecimal thresholdMax,
            LocalDateTime occurredAt
    ) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.plantId = plantId;
        this.metricType = metricType;
        this.deviation = deviation;
        this.measuredValue = measuredValue;
        this.thresholdMin = thresholdMin;
        this.thresholdMax = thresholdMax;
        this.occurredAt = occurredAt;
    }

    public static Alert open(
            String userId,
            String plantId,
            AlertMetricType metricType,
            AlertDeviation deviation,
            BigDecimal measuredValue,
            BigDecimal thresholdMin,
            BigDecimal thresholdMax,
            LocalDateTime occurredAt
    ) {
        return new Alert(
                userId,
                plantId,
                metricType,
                deviation,
                measuredValue,
                thresholdMin,
                thresholdMax,
                occurredAt
        );
    }

    /** 정상 범위로 복귀했을 때 활성 상태를 종료한다. 이미 해제된 Alert는 변경하지 않는다. */
    public void resolve(LocalDateTime resolvedAt) {
        if (this.resolvedAt == null) {
            this.resolvedAt = resolvedAt;
        }
    }

    /** 사용자가 확인했음을 기록한다. 다시 호출해도 처음 확인 시각을 유지한다. */
    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    public boolean isActive() {
        return resolvedAt == null;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getPlantId() {
        return plantId;
    }

    public AlertMetricType getMetricType() {
        return metricType;
    }

    public AlertDeviation getDeviation() {
        return deviation;
    }

    public BigDecimal getMeasuredValue() {
        return measuredValue;
    }

    public BigDecimal getThresholdMin() {
        return thresholdMin;
    }

    public BigDecimal getThresholdMax() {
        return thresholdMax;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
