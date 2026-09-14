package com.potner.plant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "species_growth_requirement")
public class SpeciesGrowthRequirement {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "requirement_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private PlantSpecies species;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "life_stage_id", nullable = false)
    private PlantLifeStage lifeStage;

    @Column(name = "soil_moisture_min_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal soilMoistureMinPct;

    @Column(name = "soil_moisture_max_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal soilMoistureMaxPct;

    @Column(name = "watering_trigger_pct", precision = 5, scale = 2)
    private BigDecimal wateringTriggerPct;

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

    @Column(name = "daily_light_min_lux_hour", precision = 14, scale = 2)
    private BigDecimal dailyLightMinLuxHour;

    @Column(name = "daily_light_max_lux_hour", precision = 14, scale = 2)
    private BigDecimal dailyLightMaxLuxHour;

    @Column(name = "daily_light_target_lux_hour", precision = 14, scale = 2, nullable = false)
    private BigDecimal dailyLightTargetLuxHour;

    @Column(name = "photoperiod_hours", precision = 4, scale = 2, nullable = false)
    private BigDecimal photoperiodHours;

    @Column(name = "watering_cycle_days", precision = 5, scale = 2)
    private BigDecimal wateringCycleDays;

    @Column(name = "recommended_watering_ml", precision = 10, scale = 2)
    private BigDecimal recommendedWateringMl;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "source_name", length = 255)
    private String sourceName;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected SpeciesGrowthRequirement() {
    }

    public String getId() {
        return id;
    }

    public PlantSpecies getSpecies() {
        return species;
    }

    public PlantLifeStage getLifeStage() {
        return lifeStage;
    }

    public BigDecimal getSoilMoistureMinPct() {
        return soilMoistureMinPct;
    }

    public BigDecimal getSoilMoistureMaxPct() {
        return soilMoistureMaxPct;
    }

    public BigDecimal getWateringTriggerPct() {
        return wateringTriggerPct;
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

    public BigDecimal getDailyLightMinLuxHour() {
        return dailyLightMinLuxHour;
    }

    public BigDecimal getDailyLightMaxLuxHour() {
        return dailyLightMaxLuxHour;
    }

    public BigDecimal getDailyLightTargetLuxHour() {
        return dailyLightTargetLuxHour;
    }

    public BigDecimal getPhotoperiodHours() {
        return photoperiodHours;
    }

    public BigDecimal getWateringCycleDays() {
        return wateringCycleDays;
    }

    public BigDecimal getRecommendedWateringMl() {
        return recommendedWateringMl;
    }

    public int getRevision() {
        return revision;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public boolean isActive() {
        return active;
    }
}
