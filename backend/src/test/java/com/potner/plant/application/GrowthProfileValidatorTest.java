package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.GrowthProfileValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrowthProfileValidatorTest {

    @Test
    void acceptsValidValuesAndNullableRanges() {
        assertThatCode(() -> GrowthProfileValidator.validate(validValues())).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidPercentagesTemperatureLightAndPhotoperiod() {
        assertInvalid(withHumidity("80", "60"));
        assertInvalid(withTemperature("-31", "29"));
        assertInvalid(withIlluminance("11000", "12000", "10000"));
        assertInvalid(withPhotoperiod("25"));
    }

    private void assertInvalid(GrowthProfileValues values) {
        assertThatThrownBy(() -> GrowthProfileValidator.validate(values))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.INVALID_GROWTH_PROFILE));
    }

    private GrowthProfileValues validValues() {
        return new GrowthProfileValues(
                decimal("40"), decimal("55"), decimal("21"), decimal("29"),
                decimal("60"), decimal("80"), null, null, decimal("10000"),
                decimal("15"), null, null, decimal("150000"),
                decimal("1"), decimal("40"), null
        );
    }

    private GrowthProfileValues withHumidity(String min, String max) {
        GrowthProfileValues value = validValues();
        return new GrowthProfileValues(
                value.soilMoistureMinPct(), value.soilMoistureMaxPct(),
                value.temperatureMinC(), value.temperatureMaxC(), decimal(min), decimal(max),
                value.illuminanceMinLux(), value.illuminanceMaxLux(), value.illuminanceTargetLux(),
                value.photoperiodHours(), value.dailyLightMinLuxHour(), value.dailyLightMaxLuxHour(),
                value.dailyLightTargetLuxHour(), value.wateringCycleDays(),
                value.wateringTriggerPct(), value.recommendedWateringMl());
    }

    private GrowthProfileValues withTemperature(String min, String max) {
        GrowthProfileValues value = validValues();
        return new GrowthProfileValues(
                value.soilMoistureMinPct(), value.soilMoistureMaxPct(), decimal(min), decimal(max),
                value.humidityMinPct(), value.humidityMaxPct(), value.illuminanceMinLux(),
                value.illuminanceMaxLux(), value.illuminanceTargetLux(), value.photoperiodHours(),
                value.dailyLightMinLuxHour(), value.dailyLightMaxLuxHour(),
                value.dailyLightTargetLuxHour(), value.wateringCycleDays(),
                value.wateringTriggerPct(), value.recommendedWateringMl());
    }

    private GrowthProfileValues withIlluminance(String min, String max, String target) {
        GrowthProfileValues value = validValues();
        return new GrowthProfileValues(
                value.soilMoistureMinPct(), value.soilMoistureMaxPct(),
                value.temperatureMinC(), value.temperatureMaxC(), value.humidityMinPct(),
                value.humidityMaxPct(), decimal(min), decimal(max), decimal(target),
                value.photoperiodHours(), value.dailyLightMinLuxHour(), value.dailyLightMaxLuxHour(),
                value.dailyLightTargetLuxHour(), value.wateringCycleDays(),
                value.wateringTriggerPct(), value.recommendedWateringMl());
    }

    private GrowthProfileValues withPhotoperiod(String photoperiod) {
        GrowthProfileValues value = validValues();
        return new GrowthProfileValues(
                value.soilMoistureMinPct(), value.soilMoistureMaxPct(),
                value.temperatureMinC(), value.temperatureMaxC(), value.humidityMinPct(),
                value.humidityMaxPct(), value.illuminanceMinLux(), value.illuminanceMaxLux(),
                value.illuminanceTargetLux(), decimal(photoperiod), value.dailyLightMinLuxHour(),
                value.dailyLightMaxLuxHour(), value.dailyLightTargetLuxHour(),
                value.wateringCycleDays(), value.wateringTriggerPct(), value.recommendedWateringMl());
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
