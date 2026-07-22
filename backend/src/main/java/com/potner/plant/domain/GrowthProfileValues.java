package com.potner.plant.domain;

import java.math.BigDecimal;

public record GrowthProfileValues(
        BigDecimal soilMoistureMinPct,
        BigDecimal soilMoistureMaxPct,
        BigDecimal temperatureMinC,
        BigDecimal temperatureMaxC,
        BigDecimal humidityMinPct,
        BigDecimal humidityMaxPct,
        BigDecimal illuminanceMinLux,
        BigDecimal illuminanceMaxLux,
        BigDecimal illuminanceTargetLux,
        BigDecimal photoperiodHours,
        BigDecimal dailyLightMinLuxHour,
        BigDecimal dailyLightMaxLuxHour,
        BigDecimal dailyLightTargetLuxHour,
        BigDecimal wateringCycleDays,
        BigDecimal wateringTriggerPct,
        BigDecimal recommendedWateringMl
) {
    public static GrowthProfileValues from(SpeciesGrowthRequirement requirement) {
        return new GrowthProfileValues(
                requirement.getSoilMoistureMinPct(),
                requirement.getSoilMoistureMaxPct(),
                requirement.getTemperatureMinC(),
                requirement.getTemperatureMaxC(),
                requirement.getHumidityMinPct(),
                requirement.getHumidityMaxPct(),
                requirement.getIlluminanceMinLux(),
                requirement.getIlluminanceMaxLux(),
                requirement.getIlluminanceTargetLux(),
                requirement.getPhotoperiodHours(),
                requirement.getDailyLightMinLuxHour(),
                requirement.getDailyLightMaxLuxHour(),
                requirement.getDailyLightTargetLuxHour(),
                requirement.getWateringCycleDays(),
                requirement.getWateringTriggerPct(),
                requirement.getRecommendedWateringMl()
        );
    }

    public static GrowthProfileValues from(PlantGrowthProfile profile) {
        return new GrowthProfileValues(
                profile.getSoilMoistureMinPct(),
                profile.getSoilMoistureMaxPct(),
                profile.getTemperatureMinC(),
                profile.getTemperatureMaxC(),
                profile.getHumidityMinPct(),
                profile.getHumidityMaxPct(),
                profile.getIlluminanceMinLux(),
                profile.getIlluminanceMaxLux(),
                profile.getIlluminanceTargetLux(),
                profile.getPhotoperiodHours(),
                profile.getDailyLightMinLuxHour(),
                profile.getDailyLightMaxLuxHour(),
                profile.getDailyLightTargetLuxHour(),
                profile.getWateringCycleDays(),
                profile.getWateringTriggerPct(),
                profile.getRecommendedWateringMl()
        );
    }
}
