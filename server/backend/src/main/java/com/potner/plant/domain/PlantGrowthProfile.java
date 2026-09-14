package com.potner.plant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "plant_growth_profile")
public class PlantGrowthProfile {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @MapsId
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_requirement_id", nullable = false)
    private SpeciesGrowthRequirement sourceRequirement;

    @Column(name = "source_revision", nullable = false)
    private int sourceRevision;

    @Column(name = "soil_moisture_min_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal soilMoistureMinPct;

    @Column(name = "soil_moisture_max_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal soilMoistureMaxPct;

    @Column(name = "temperature_min_c", precision = 5, scale = 2, nullable = false)
    private BigDecimal temperatureMinC;

    @Column(name = "temperature_max_c", precision = 5, scale = 2, nullable = false)
    private BigDecimal temperatureMaxC;

    @Column(name = "humidity_min_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal humidityMinPct;

    @Column(name = "humidity_max_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal humidityMaxPct;

    @Column(name = "illuminance_min_lux", precision = 12, scale = 2)
    private BigDecimal illuminanceMinLux;

    @Column(name = "illuminance_max_lux", precision = 12, scale = 2)
    private BigDecimal illuminanceMaxLux;

    @Column(name = "illuminance_target_lux", precision = 12, scale = 2, nullable = false)
    private BigDecimal illuminanceTargetLux;

    @Column(name = "photoperiod_hours", precision = 4, scale = 2, nullable = false)
    private BigDecimal photoperiodHours;

    @Column(name = "daily_light_min_lux_hour", precision = 14, scale = 2)
    private BigDecimal dailyLightMinLuxHour;

    @Column(name = "daily_light_max_lux_hour", precision = 14, scale = 2)
    private BigDecimal dailyLightMaxLuxHour;

    @Column(name = "daily_light_target_lux_hour", precision = 14, scale = 2, nullable = false)
    private BigDecimal dailyLightTargetLuxHour;

    @Column(name = "watering_cycle_days", precision = 5, scale = 2)
    private BigDecimal wateringCycleDays;

    @Column(name = "watering_trigger_pct", precision = 5, scale = 2)
    private BigDecimal wateringTriggerPct;

    @Column(name = "recommended_watering_ml", precision = 10, scale = 2)
    private BigDecimal recommendedWateringMl;

    @Column(name = "custom_override", nullable = false)
    private boolean customized;

    @Column(name = "customized_at")
    private LocalDateTime customizedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PlantGrowthProfile() {
    }

    private PlantGrowthProfile(Plant plant, SpeciesGrowthRequirement requirement, LocalDateTime now) {
        this.plant = plant;
        this.plantId = plant.getId();
        this.createdAt = now;
        applyRequirement(requirement, now);
    }

    public static PlantGrowthProfile create(
            Plant plant,
            SpeciesGrowthRequirement requirement,
            LocalDateTime now
    ) {
        return new PlantGrowthProfile(plant, requirement, now);
    }

    public void applyRequirement(SpeciesGrowthRequirement requirement, LocalDateTime now) {
        this.sourceRequirement = requirement;
        this.sourceRevision = requirement.getRevision();
        applyValues(GrowthProfileValues.from(requirement));
        this.customized = false;
        this.customizedAt = null;
        this.updatedAt = now;
    }

    public void customize(GrowthProfileValues values, LocalDateTime now) {
        applyValues(values);
        this.customized = true;
        this.customizedAt = now;
        this.updatedAt = now;
    }

    private void applyValues(GrowthProfileValues values) {
        this.soilMoistureMinPct = values.soilMoistureMinPct();
        this.soilMoistureMaxPct = values.soilMoistureMaxPct();
        this.temperatureMinC = values.temperatureMinC();
        this.temperatureMaxC = values.temperatureMaxC();
        this.humidityMinPct = values.humidityMinPct();
        this.humidityMaxPct = values.humidityMaxPct();
        this.illuminanceMinLux = values.illuminanceMinLux();
        this.illuminanceMaxLux = values.illuminanceMaxLux();
        this.illuminanceTargetLux = values.illuminanceTargetLux();
        this.photoperiodHours = values.photoperiodHours();
        this.dailyLightMinLuxHour = values.dailyLightMinLuxHour();
        this.dailyLightMaxLuxHour = values.dailyLightMaxLuxHour();
        this.dailyLightTargetLuxHour = values.dailyLightTargetLuxHour();
        this.wateringCycleDays = values.wateringCycleDays();
        this.wateringTriggerPct = values.wateringTriggerPct();
        this.recommendedWateringMl = values.recommendedWateringMl();
    }

    public String getPlantId() {
        return plantId;
    }

    public Plant getPlant() {
        return plant;
    }

    public SpeciesGrowthRequirement getSourceRequirement() {
        return sourceRequirement;
    }

    public int getSourceRevision() {
        return sourceRevision;
    }

    public BigDecimal getSoilMoistureMinPct() {
        return soilMoistureMinPct;
    }

    public BigDecimal getSoilMoistureMaxPct() {
        return soilMoistureMaxPct;
    }

    public BigDecimal getTemperatureMinC() {
        return temperatureMinC;
    }

    public BigDecimal getTemperatureMaxC() {
        return temperatureMaxC;
    }

    public BigDecimal getHumidityMinPct() {
        return humidityMinPct;
    }

    public BigDecimal getHumidityMaxPct() {
        return humidityMaxPct;
    }

    public BigDecimal getIlluminanceMinLux() {
        return illuminanceMinLux;
    }

    public BigDecimal getIlluminanceMaxLux() {
        return illuminanceMaxLux;
    }

    public BigDecimal getIlluminanceTargetLux() {
        return illuminanceTargetLux;
    }

    public BigDecimal getPhotoperiodHours() {
        return photoperiodHours;
    }

    public BigDecimal getDailyLightMinLuxHour() {
        return dailyLightMinLuxHour;
    }

    public BigDecimal getDailyLightMaxLuxHour() {
        return dailyLightMaxLuxHour;
    }

    public BigDecimal getDailyLightTargetLuxHour() {
        return dailyLightTargetLuxHour;
    }

    public BigDecimal getWateringCycleDays() {
        return wateringCycleDays;
    }

    public BigDecimal getWateringTriggerPct() {
        return wateringTriggerPct;
    }

    public BigDecimal getRecommendedWateringMl() {
        return recommendedWateringMl;
    }

    public boolean isCustomized() {
        return customized;
    }

    public LocalDateTime getCustomizedAt() {
        return customizedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
