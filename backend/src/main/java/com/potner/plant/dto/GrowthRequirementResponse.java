package com.potner.plant.dto;

import com.potner.plant.domain.PlantLifeStage;
import com.potner.plant.domain.PlantSpecies;
import com.potner.plant.domain.SpeciesGrowthRequirement;

import java.math.BigDecimal;

public record GrowthRequirementResponse(
        String speciesId,
        String speciesName,
        String lifeStageId,
        String lifeStageCode,
        String lifeStageName,
        SoilMoisture soilMoisture,
        Watering watering,
        Temperature temperature,
        Humidity humidity,
        Illuminance illuminance,
        DailyLight dailyLight,
        BigDecimal photoperiodHours,
        int revision,
        String sourceName,
        String sourceUrl
) {
    public static GrowthRequirementResponse from(SpeciesGrowthRequirement requirement) {
        PlantSpecies species = requirement.getSpecies();
        PlantLifeStage lifeStage = requirement.getLifeStage();
        return new GrowthRequirementResponse(
                species.getId(),
                species.getName(),
                lifeStage.getId(),
                lifeStage.getCode(),
                lifeStage.getName(),
                new SoilMoisture(
                        requirement.getSoilMoistureMinPct(),
                        requirement.getSoilMoistureMaxPct()),
                new Watering(
                        requirement.getWateringTriggerPct(),
                        requirement.getWateringCycleDays(),
                        requirement.getRecommendedWateringMl()),
                new Temperature(
                        requirement.getTemperatureMinC(),
                        requirement.getTemperatureMaxC()),
                new Humidity(
                        requirement.getHumidityMinPct(),
                        requirement.getHumidityMaxPct()),
                new Illuminance(
                        requirement.getIlluminanceMinLux(),
                        requirement.getIlluminanceMaxLux(),
                        requirement.getIlluminanceTargetLux()),
                new DailyLight(
                        requirement.getDailyLightMinLuxHour(),
                        requirement.getDailyLightMaxLuxHour(),
                        requirement.getDailyLightTargetLuxHour()),
                requirement.getPhotoperiodHours(),
                requirement.getRevision(),
                requirement.getSourceName(),
                requirement.getSourceUrl()
        );
    }

    public record SoilMoisture(BigDecimal minPct, BigDecimal maxPct) {
    }

    public record Watering(
            BigDecimal triggerPct,
            BigDecimal cycleDays,
            BigDecimal recommendedVolumeMl
    ) {
    }

    public record Temperature(BigDecimal minC, BigDecimal maxC) {
    }

    public record Humidity(BigDecimal minPct, BigDecimal maxPct) {
    }

    public record Illuminance(BigDecimal minLux, BigDecimal maxLux, BigDecimal targetLux) {
    }

    public record DailyLight(BigDecimal minLuxHour, BigDecimal maxLuxHour, BigDecimal targetLuxHour) {
    }
}
