package com.potner.plant.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.potner.plant.domain.GrowthProfileValues;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

public class UpdateGrowthProfileRequest {

    @JsonIgnore
    private final Set<String> presentFields = new HashSet<>();

    private BigDecimal soilMoistureMinPct;
    private BigDecimal soilMoistureMaxPct;
    private BigDecimal temperatureMinC;
    private BigDecimal temperatureMaxC;
    private BigDecimal humidityMinPct;
    private BigDecimal humidityMaxPct;
    private BigDecimal illuminanceMinLux;
    private BigDecimal illuminanceMaxLux;
    private BigDecimal illuminanceTargetLux;
    private BigDecimal photoperiodHours;
    private BigDecimal dailyLightMinLuxHour;
    private BigDecimal dailyLightMaxLuxHour;
    private BigDecimal dailyLightTargetLuxHour;
    private BigDecimal wateringCycleDays;
    private BigDecimal wateringTriggerPct;
    private BigDecimal recommendedWateringMl;

    @JsonSetter("soilMoistureMinPct")
    public void setSoilMoistureMinPct(BigDecimal value) {
        presentFields.add("soilMoistureMinPct");
        this.soilMoistureMinPct = value;
    }

    @JsonSetter("soilMoistureMaxPct")
    public void setSoilMoistureMaxPct(BigDecimal value) {
        presentFields.add("soilMoistureMaxPct");
        this.soilMoistureMaxPct = value;
    }

    @JsonSetter("temperatureMinC")
    public void setTemperatureMinC(BigDecimal value) {
        presentFields.add("temperatureMinC");
        this.temperatureMinC = value;
    }

    @JsonSetter("temperatureMaxC")
    public void setTemperatureMaxC(BigDecimal value) {
        presentFields.add("temperatureMaxC");
        this.temperatureMaxC = value;
    }

    @JsonSetter("humidityMinPct")
    public void setHumidityMinPct(BigDecimal value) {
        presentFields.add("humidityMinPct");
        this.humidityMinPct = value;
    }

    @JsonSetter("humidityMaxPct")
    public void setHumidityMaxPct(BigDecimal value) {
        presentFields.add("humidityMaxPct");
        this.humidityMaxPct = value;
    }

    @JsonSetter("illuminanceMinLux")
    public void setIlluminanceMinLux(BigDecimal value) {
        presentFields.add("illuminanceMinLux");
        this.illuminanceMinLux = value;
    }

    @JsonSetter("illuminanceMaxLux")
    public void setIlluminanceMaxLux(BigDecimal value) {
        presentFields.add("illuminanceMaxLux");
        this.illuminanceMaxLux = value;
    }

    @JsonSetter("illuminanceTargetLux")
    public void setIlluminanceTargetLux(BigDecimal value) {
        presentFields.add("illuminanceTargetLux");
        this.illuminanceTargetLux = value;
    }

    @JsonSetter("photoperiodHours")
    public void setPhotoperiodHours(BigDecimal value) {
        presentFields.add("photoperiodHours");
        this.photoperiodHours = value;
    }

    @JsonSetter("dailyLightMinLuxHour")
    public void setDailyLightMinLuxHour(BigDecimal value) {
        presentFields.add("dailyLightMinLuxHour");
        this.dailyLightMinLuxHour = value;
    }

    @JsonSetter("dailyLightMaxLuxHour")
    public void setDailyLightMaxLuxHour(BigDecimal value) {
        presentFields.add("dailyLightMaxLuxHour");
        this.dailyLightMaxLuxHour = value;
    }

    @JsonSetter("dailyLightTargetLuxHour")
    public void setDailyLightTargetLuxHour(BigDecimal value) {
        presentFields.add("dailyLightTargetLuxHour");
        this.dailyLightTargetLuxHour = value;
    }

    @JsonSetter("wateringCycleDays")
    public void setWateringCycleDays(BigDecimal value) {
        presentFields.add("wateringCycleDays");
        this.wateringCycleDays = value;
    }

    @JsonSetter("wateringTriggerPct")
    public void setWateringTriggerPct(BigDecimal value) {
        presentFields.add("wateringTriggerPct");
        this.wateringTriggerPct = value;
    }

    @JsonSetter("recommendedWateringMl")
    public void setRecommendedWateringMl(BigDecimal value) {
        presentFields.add("recommendedWateringMl");
        this.recommendedWateringMl = value;
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

    @JsonIgnore
    public boolean isEmpty() {
        return presentFields.isEmpty();
    }

    public GrowthProfileValues merge(GrowthProfileValues current) {
        return new GrowthProfileValues(
                value("soilMoistureMinPct", soilMoistureMinPct, current.soilMoistureMinPct()),
                value("soilMoistureMaxPct", soilMoistureMaxPct, current.soilMoistureMaxPct()),
                value("temperatureMinC", temperatureMinC, current.temperatureMinC()),
                value("temperatureMaxC", temperatureMaxC, current.temperatureMaxC()),
                value("humidityMinPct", humidityMinPct, current.humidityMinPct()),
                value("humidityMaxPct", humidityMaxPct, current.humidityMaxPct()),
                value("illuminanceMinLux", illuminanceMinLux, current.illuminanceMinLux()),
                value("illuminanceMaxLux", illuminanceMaxLux, current.illuminanceMaxLux()),
                value("illuminanceTargetLux", illuminanceTargetLux, current.illuminanceTargetLux()),
                value("photoperiodHours", photoperiodHours, current.photoperiodHours()),
                value("dailyLightMinLuxHour", dailyLightMinLuxHour, current.dailyLightMinLuxHour()),
                value("dailyLightMaxLuxHour", dailyLightMaxLuxHour, current.dailyLightMaxLuxHour()),
                value("dailyLightTargetLuxHour", dailyLightTargetLuxHour, current.dailyLightTargetLuxHour()),
                value("wateringCycleDays", wateringCycleDays, current.wateringCycleDays()),
                value("wateringTriggerPct", wateringTriggerPct, current.wateringTriggerPct()),
                value("recommendedWateringMl", recommendedWateringMl, current.recommendedWateringMl())
        );
    }

    private BigDecimal value(String field, BigDecimal requested, BigDecimal current) {
        return presentFields.contains(field) ? requested : current;
    }
}
