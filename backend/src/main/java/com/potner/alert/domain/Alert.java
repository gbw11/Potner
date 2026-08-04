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

    /** 물 부족처럼 측정값이 없는 지표는 NULL 이다. 센서 알림은 DB CHECK 가 NOT NULL 을 강제한다. */
    @Column(name = "measured_value", precision = 14, scale = 4)
    private BigDecimal measuredValue;

    @Column(name = "threshold_min", precision = 14, scale = 4)
    private BigDecimal thresholdMin;

    @Column(name = "threshold_max", precision = 14, scale = 4)
    private BigDecimal thresholdMax;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /**
     * 사용자가 목록에서 치운 시각이다. 목록 조회만 이 값을 본다.
     *
     * <p>행복도 점수·자동화·일기 생성은 이 값과 무관하게 알림을 읽는다. 치웠다고 지난 점수가
     * 바뀌거나 급수가 다시 돌면 안 된다 — 그 근거는 V30 마이그레이션 주석에 있다.
     */
    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

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

    /**
     * 스테이션 물 부족 알림을 연다.
     *
     * <p>물 부족 보고는 불리언이라 측정값과 기준이 없다. 합성값으로 채우면 앱 알림 상세에
     * 가짜 숫자가 노출되므로 비워 둔다. 방향은 항상 {@code LOW} 다 — "물이 너무 많다" 는
     * 보고 자체가 없다.
     */
    public static Alert openStationWaterLow(String userId, String plantId, LocalDateTime occurredAt) {
        return new Alert(
                userId,
                plantId,
                AlertMetricType.STATION_WATER_LOW,
                AlertDeviation.LOW,
                null,
                null,
                null,
                occurredAt
        );
    }

    /**
     * 배수트레이 비움 알림을 연다.
     *
     * <p>센서가 아니라 누적 급수량으로 판정하지만 측정값과 기준이 실제로 있으므로 세 값을 모두
     * 채운다. 앱이 "1,200 / 2,800ml" 같은 진행 상황을 보여줄 수 있다.
     *
     * <p>방향은 항상 {@code HIGH} 다 — 트레이는 넘칠 때만 문제가 된다. 하한은 0 이다.
     */
    public static Alert openDrainageTray(
            String userId,
            String plantId,
            BigDecimal accumulatedMl,
            BigDecimal thresholdMl,
            LocalDateTime occurredAt
    ) {
        return new Alert(
                userId,
                plantId,
                AlertMetricType.DRAINAGE_TRAY,
                AlertDeviation.HIGH,
                accumulatedMl,
                BigDecimal.ZERO,
                thresholdMl,
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

    /**
     * 사용자가 목록에서 치웠음을 기록한다. 목록 조회에서만 빠지고 행은 남는다.
     *
     * <p>읽음도 함께 남긴다. 목록에서 사라졌는데 안 읽음으로 남으면 홈의 안 읽음 배지만 켜져 있고
     * 사용자는 무엇이 남았는지 찾을 수 없다.
     *
     * <p>{@link #resolve} 와 다른 값이다. 그쪽은 "이상이 해소됨" 이라 자동 급수·말리기가 읽는
     * 값이고, 이쪽은 "사용자가 치움" 이라 목록 조회만 본다. 재활용하면 알림을 밀어낸 것이 자동화를
     * 다시 돌린다.
     */
    public void dismiss(LocalDateTime dismissedAt) {
        if (this.dismissedAt == null) {
            this.dismissedAt = dismissedAt;
        }
        markRead(dismissedAt);
    }

    /**
     * 치운 것을 되돌린다.
     *
     * <p>읽음은 되돌리지 않는다. 사용자가 그 알림을 실제로 봤다는 사실은 되돌리기로 사라지지
     * 않는다.
     */
    public void restore() {
        this.dismissedAt = null;
    }

    public boolean isActive() {
        return resolvedAt == null;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public boolean isDismissed() {
        return dismissedAt != null;
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

    public LocalDateTime getDismissedAt() {
        return dismissedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
