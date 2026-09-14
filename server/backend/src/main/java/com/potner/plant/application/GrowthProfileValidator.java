package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.GrowthProfileValues;

import java.math.BigDecimal;

public final class GrowthProfileValidator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_TEMPERATURE = new BigDecimal("-30");
    private static final BigDecimal MAX_TEMPERATURE = new BigDecimal("80");
    private static final BigDecimal MAX_PHOTOPERIOD = new BigDecimal("24");

    private GrowthProfileValidator() {
    }

    public static void validate(GrowthProfileValues values) {
        require(values.soilMoistureMinPct(), values.soilMoistureMaxPct());
        require(values.temperatureMinC(), values.temperatureMaxC());
        require(values.humidityMinPct(), values.humidityMaxPct());
        require(values.illuminanceTargetLux(), values.dailyLightTargetLuxHour());
        require(values.photoperiodHours());

        percentageRange(values.soilMoistureMinPct(), values.soilMoistureMaxPct());
        percentageRange(values.humidityMinPct(), values.humidityMaxPct());

        if (values.temperatureMinC().compareTo(MIN_TEMPERATURE) < 0
                || values.temperatureMaxC().compareTo(MAX_TEMPERATURE) > 0
                || values.temperatureMinC().compareTo(values.temperatureMaxC()) > 0) {
            invalid();
        }

        nullablePercentage(values.wateringTriggerPct());
        nullablePositive(values.wateringCycleDays());
        nullablePositive(values.recommendedWateringMl());

        rangedTarget(
                values.illuminanceMinLux(),
                values.illuminanceMaxLux(),
                values.illuminanceTargetLux());
        rangedTarget(
                values.dailyLightMinLuxHour(),
                values.dailyLightMaxLuxHour(),
                values.dailyLightTargetLuxHour());

        if (values.photoperiodHours().compareTo(ZERO) <= 0
                || values.photoperiodHours().compareTo(MAX_PHOTOPERIOD) > 0) {
            invalid();
        }
    }

    private static void percentageRange(BigDecimal min, BigDecimal max) {
        if (min.compareTo(ZERO) < 0
                || max.compareTo(ONE_HUNDRED) > 0
                || min.compareTo(max) > 0) {
            invalid();
        }
    }

    private static void nullablePercentage(BigDecimal value) {
        if (value != null && (value.compareTo(ZERO) < 0 || value.compareTo(ONE_HUNDRED) > 0)) {
            invalid();
        }
    }

    private static void nullablePositive(BigDecimal value) {
        if (value != null && value.compareTo(ZERO) <= 0) {
            invalid();
        }
    }

    private static void rangedTarget(BigDecimal min, BigDecimal max, BigDecimal target) {
        if (target.compareTo(ZERO) < 0) {
            invalid();
        }
        if (min == null && max == null) {
            return;
        }
        if (min == null || max == null
                || min.compareTo(ZERO) < 0
                || min.compareTo(target) > 0
                || target.compareTo(max) > 0) {
            invalid();
        }
    }

    private static void require(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value == null) {
                invalid();
            }
        }
    }

    private static void invalid() {
        throw new BusinessException(ErrorCode.INVALID_GROWTH_PROFILE);
    }
}
