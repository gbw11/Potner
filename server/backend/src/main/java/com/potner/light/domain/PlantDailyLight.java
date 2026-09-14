package com.potner.light.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "plant_daily_light")
public class PlantDailyLight {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "daily_light_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @Column(name = "light_date", nullable = false)
    private LocalDate lightDate;

    @Column(name = "accumulated_lux_hour", precision = 14, scale = 2, nullable = false)
    private BigDecimal accumulatedLuxHour;

    @Column(name = "light_hours", precision = 5, scale = 2, nullable = false)
    private BigDecimal lightHours;

    @Column(name = "coverage_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal coveragePct;

    @Column(name = "sample_count", nullable = false, columnDefinition = "int unsigned")
    private long sampleCount;

    @Column(name = "target_lux_hour", precision = 14, scale = 2, nullable = false)
    private BigDecimal targetLuxHour;

    @Column(name = "target_photoperiod_hours", precision = 4, scale = 2, nullable = false)
    private BigDecimal targetPhotoperiodHours;

    @Column(name = "threshold_min_lux_hour", precision = 14, scale = 2)
    private BigDecimal thresholdMinLuxHour;

    @Column(name = "threshold_max_lux_hour", precision = 14, scale = 2)
    private BigDecimal thresholdMaxLuxHour;

    @Enumerated(EnumType.STRING)
    @Column(name = "light_status", length = 20, nullable = false)
    private DailyLightStatus lightStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "photoperiod_status", length = 20, nullable = false)
    private DailyLightStatus photoperiodStatus;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected PlantDailyLight() {
    }

    public static PlantDailyLight create(String plantId, LocalDate lightDate) {
        PlantDailyLight daily = new PlantDailyLight();
        daily.id = UUID.randomUUID().toString();
        daily.plantId = plantId;
        daily.lightDate = lightDate;
        return daily;
    }

    /** 같은 날짜를 다시 집계하면 기존 행을 갱신한다. */
    public void apply(
            BigDecimal accumulatedLuxHour,
            BigDecimal lightHours,
            BigDecimal coveragePct,
            long sampleCount,
            BigDecimal targetLuxHour,
            BigDecimal targetPhotoperiodHours,
            BigDecimal thresholdMinLuxHour,
            BigDecimal thresholdMaxLuxHour,
            DailyLightStatus lightStatus,
            DailyLightStatus photoperiodStatus,
            LocalDateTime computedAt
    ) {
        this.accumulatedLuxHour = accumulatedLuxHour;
        this.lightHours = lightHours;
        this.coveragePct = coveragePct;
        this.sampleCount = sampleCount;
        this.targetLuxHour = targetLuxHour;
        this.targetPhotoperiodHours = targetPhotoperiodHours;
        this.thresholdMinLuxHour = thresholdMinLuxHour;
        this.thresholdMaxLuxHour = thresholdMaxLuxHour;
        this.lightStatus = lightStatus;
        this.photoperiodStatus = photoperiodStatus;
        this.computedAt = computedAt;
    }

    public String getId() {
        return id;
    }

    public String getPlantId() {
        return plantId;
    }

    public LocalDate getLightDate() {
        return lightDate;
    }

    public BigDecimal getAccumulatedLuxHour() {
        return accumulatedLuxHour;
    }

    public BigDecimal getLightHours() {
        return lightHours;
    }

    public BigDecimal getCoveragePct() {
        return coveragePct;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public BigDecimal getTargetLuxHour() {
        return targetLuxHour;
    }

    public BigDecimal getTargetPhotoperiodHours() {
        return targetPhotoperiodHours;
    }

    public BigDecimal getThresholdMinLuxHour() {
        return thresholdMinLuxHour;
    }

    public BigDecimal getThresholdMaxLuxHour() {
        return thresholdMaxLuxHour;
    }

    public DailyLightStatus getLightStatus() {
        return lightStatus;
    }

    public DailyLightStatus getPhotoperiodStatus() {
        return photoperiodStatus;
    }

    public LocalDateTime getComputedAt() {
        return computedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
