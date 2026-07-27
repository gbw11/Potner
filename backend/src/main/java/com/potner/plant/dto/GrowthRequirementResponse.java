package com.potner.plant.dto;

import com.potner.plant.domain.PlantLifeStage;
import com.potner.plant.domain.PlantSpecies;
import com.potner.plant.domain.SpeciesGrowthRequirement;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
                DailyLight.of(
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

    /**
     * 하루 누적 광량 기준이다. 목표 대비 비율을 함께 계산해 내려준다.
     *
     * <p>앱은 lux·h 대신 이 비율을 보여준다. lux·h는 값이 수십만 단위라 사용자가 해석하기 어렵고,
     * V7 마이그레이션이 허용 범위를 목표값 × 비율로 채웠으므로 비율이 원래 의미에 더 가깝다.
     * 저장은 그대로 lux·h로 하며, 이 비율은 표시용 파생값이다.
     */
    public record DailyLight(
            BigDecimal minLuxHour,
            BigDecimal maxLuxHour,
            BigDecimal targetLuxHour,
            BigDecimal minPctOfTarget,
            BigDecimal maxPctOfTarget
    ) {
        private static final BigDecimal HUNDRED = new BigDecimal("100");

        public static DailyLight of(
                BigDecimal minLuxHour,
                BigDecimal maxLuxHour,
                BigDecimal targetLuxHour
        ) {
            return new DailyLight(
                    minLuxHour,
                    maxLuxHour,
                    targetLuxHour,
                    pctOfTarget(minLuxHour, targetLuxHour),
                    pctOfTarget(maxLuxHour, targetLuxHour)
            );
        }

        /** 허용 범위가 비어 있거나 목표가 0이면 비율을 만들 수 없으므로 null이다. */
        private static BigDecimal pctOfTarget(BigDecimal value, BigDecimal targetLuxHour) {
            if (value == null || targetLuxHour == null || targetLuxHour.signum() == 0) {
                return null;
            }
            return value.multiply(HUNDRED).divide(targetLuxHour, 2, RoundingMode.HALF_UP);
        }
    }
}
