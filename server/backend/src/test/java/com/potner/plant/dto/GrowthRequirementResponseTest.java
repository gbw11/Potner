package com.potner.plant.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GrowthRequirementResponseTest {

    @Test
    void dailyLightCarriesPercentOfTargetSoAppCanAvoidLuxHour() {
        // V7이 허용 범위를 목표값 × 0.70 / × 1.30 으로 채웠으므로 비율이 원래 의미에 가깝다.
        GrowthRequirementResponse.DailyLight dailyLight = GrowthRequirementResponse.DailyLight.of(
                new BigDecimal("105000.00"),
                new BigDecimal("195000.00"),
                new BigDecimal("150000.00")
        );

        assertThat(dailyLight.minPctOfTarget()).isEqualByComparingTo("70.00");
        assertThat(dailyLight.maxPctOfTarget()).isEqualByComparingTo("130.00");
        // 저장 값은 그대로 유지한다. 비율은 표시용 파생값이다.
        assertThat(dailyLight.minLuxHour()).isEqualByComparingTo("105000.00");
        assertThat(dailyLight.maxLuxHour()).isEqualByComparingTo("195000.00");
    }

    @Test
    void dailyLightPercentIsNullWhenBandIsEmpty() {
        // V7 이전 데이터처럼 허용 범위가 비어 있으면 비율을 만들 수 없다.
        GrowthRequirementResponse.DailyLight dailyLight = GrowthRequirementResponse.DailyLight.of(
                null,
                null,
                new BigDecimal("150000.00")
        );

        assertThat(dailyLight.minPctOfTarget()).isNull();
        assertThat(dailyLight.maxPctOfTarget()).isNull();
        assertThat(dailyLight.targetLuxHour()).isEqualByComparingTo("150000.00");
    }

    @Test
    void dailyLightPercentIsNullWhenTargetIsZeroInsteadOfFailing() {
        GrowthRequirementResponse.DailyLight dailyLight = GrowthRequirementResponse.DailyLight.of(
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                BigDecimal.ZERO
        );

        assertThat(dailyLight.minPctOfTarget()).isNull();
        assertThat(dailyLight.maxPctOfTarget()).isNull();
    }
}
