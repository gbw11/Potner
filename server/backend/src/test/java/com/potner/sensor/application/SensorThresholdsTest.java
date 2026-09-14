package com.potner.sensor.application;

import com.potner.plant.domain.GrowthProfileValues;
import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SensorThresholdsTest {

    @Test
    void temperatureThresholdsComeFromAppliedProfile() {
        SensorThresholds thresholds = SensorThresholds.of(SensorType.TEMPERATURE, profile());

        assertThat(thresholds.min()).isEqualByComparingTo("21.00");
        assertThat(thresholds.max()).isEqualByComparingTo("29.00");
        assertThat(thresholds.judgeable()).isTrue();
    }

    @Test
    void humidityAndSoilMoistureUsePercentThresholds() {
        assertThat(SensorThresholds.of(SensorType.HUMIDITY, profile()).min())
                .isEqualByComparingTo("60.00");
        assertThat(SensorThresholds.of(SensorType.HUMIDITY, profile()).max())
                .isEqualByComparingTo("80.00");
        assertThat(SensorThresholds.of(SensorType.SOIL_MOISTURE, profile()).min())
                .isEqualByComparingTo("40.00");
        assertThat(SensorThresholds.of(SensorType.SOIL_MOISTURE, profile()).max())
                .isEqualByComparingTo("55.00");
    }

    @Test
    void valueBelowMinimumIsLowAndValueAboveMaximumIsHigh() {
        SensorThresholds thresholds = SensorThresholds.of(SensorType.TEMPERATURE, profile());

        assertThat(thresholds.evaluate(new BigDecimal("20.99"))).isEqualTo(SensorStatus.LOW);
        assertThat(thresholds.evaluate(new BigDecimal("29.01"))).isEqualTo(SensorStatus.HIGH);
    }

    @Test
    void boundaryValuesAreNormal() {
        SensorThresholds thresholds = SensorThresholds.of(SensorType.TEMPERATURE, profile());

        assertThat(thresholds.evaluate(new BigDecimal("21.00"))).isEqualTo(SensorStatus.NORMAL);
        assertThat(thresholds.evaluate(new BigDecimal("21.0000"))).isEqualTo(SensorStatus.NORMAL);
        assertThat(thresholds.evaluate(new BigDecimal("25"))).isEqualTo(SensorStatus.NORMAL);
        assertThat(thresholds.evaluate(new BigDecimal("29.00"))).isEqualTo(SensorStatus.NORMAL);
    }

    @Test
    void illuminanceIsNotJudgedEvenWhenProfileHasInstantBounds() {
        // 밤에는 0 lux가 정상이므로 순간값 판정 자체를 하지 않는다.
        // 사용자가 상·하한을 직접 채워 넣었더라도 정책은 바뀌지 않는다.
        GrowthProfileValues withBounds = profile(
                new BigDecimal("5000.00"),
                new BigDecimal("20000.00")
        );
        SensorThresholds thresholds = SensorThresholds.of(SensorType.ILLUMINANCE, withBounds);

        assertThat(thresholds.judgeable()).isFalse();
        assertThat(thresholds.min()).isNull();
        assertThat(thresholds.max()).isNull();
        assertThat(thresholds.evaluate(new BigDecimal("8200")))
                .isEqualTo(SensorStatus.NOT_APPLICABLE);
    }

    @Test
    void missingThresholdsFallBackToNotApplicable() {
        GrowthProfileValues incomplete = new GrowthProfileValues(
                null, null,
                null, null,
                null, null,
                null, null, new BigDecimal("10000.00"),
                new BigDecimal("15.00"),
                null, null, new BigDecimal("150000.00"),
                null, null, null
        );

        assertThat(SensorThresholds.of(SensorType.TEMPERATURE, incomplete).evaluate(BigDecimal.ONE))
                .isEqualTo(SensorStatus.NOT_APPLICABLE);
    }

    private GrowthProfileValues profile() {
        return profile(null, null);
    }

    private GrowthProfileValues profile(BigDecimal illuminanceMinLux, BigDecimal illuminanceMaxLux) {
        return new GrowthProfileValues(
                new BigDecimal("40.00"),
                new BigDecimal("55.00"),
                new BigDecimal("21.00"),
                new BigDecimal("29.00"),
                new BigDecimal("60.00"),
                new BigDecimal("80.00"),
                illuminanceMinLux,
                illuminanceMaxLux,
                new BigDecimal("10000.00"),
                new BigDecimal("15.00"),
                null,
                null,
                new BigDecimal("150000.00"),
                new BigDecimal("1.00"),
                new BigDecimal("40.00"),
                null
        );
    }
}
