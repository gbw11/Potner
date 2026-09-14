package com.potner.plant.dto;

import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantLifeStage;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GrowthProfileResponse(
        String plantId,
        GrowthStageResponse lifeStage,
        GrowthRequirementResponse.SoilMoisture soilMoisture,
        GrowthRequirementResponse.Watering watering,
        GrowthRequirementResponse.Temperature temperature,
        GrowthRequirementResponse.Humidity humidity,
        GrowthRequirementResponse.Illuminance illuminance,
        GrowthRequirementResponse.DailyLight dailyLight,
        BigDecimal photoperiodHours,
        String sourceRequirementId,
        int sourceRevision,
        boolean customized,
        LocalDateTime customizedAt,
        LocalDateTime updatedAt
) {
    public static GrowthProfileResponse from(PlantGrowthProfile profile, PlantLifeStage lifeStage) {
        return new GrowthProfileResponse(
                profile.getPlantId(),
                GrowthStageResponse.from(lifeStage),
                new GrowthRequirementResponse.SoilMoisture(
                        profile.getSoilMoistureMinPct(),
                        profile.getSoilMoistureMaxPct()),
                new GrowthRequirementResponse.Watering(
                        profile.getWateringTriggerPct(),
                        profile.getWateringCycleDays(),
                        profile.getRecommendedWateringMl()),
                new GrowthRequirementResponse.Temperature(
                        profile.getTemperatureMinC(),
                        profile.getTemperatureMaxC()),
                new GrowthRequirementResponse.Humidity(
                        profile.getHumidityMinPct(),
                        profile.getHumidityMaxPct()),
                new GrowthRequirementResponse.Illuminance(
                        profile.getIlluminanceMinLux(),
                        profile.getIlluminanceMaxLux(),
                        profile.getIlluminanceTargetLux()),
                GrowthRequirementResponse.DailyLight.of(
                        profile.getDailyLightMinLuxHour(),
                        profile.getDailyLightMaxLuxHour(),
                        profile.getDailyLightTargetLuxHour()),
                profile.getPhotoperiodHours(),
                profile.getSourceRequirement().getId(),
                profile.getSourceRevision(),
                profile.isCustomized(),
                profile.getCustomizedAt(),
                profile.getUpdatedAt()
        );
    }
}
