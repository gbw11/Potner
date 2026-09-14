package com.potner.sensor.application;

import com.potner.plant.domain.GrowthProfileValues;
import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;

import java.math.BigDecimal;

/**
 * 식물에 적용 중인 생육 기준에서 뽑아낸 센서별 판정 구간이다.
 * 사용자가 맞춤값을 수정했다면 종 기본값이 아니라 수정된 값이 그대로 반영된다.
 */
public record SensorThresholds(BigDecimal min, BigDecimal max) {

    private static final SensorThresholds NONE = new SensorThresholds(null, null);

    public static SensorThresholds of(SensorType sensorType, GrowthProfileValues profile) {
        return switch (sensorType) {
            case TEMPERATURE -> new SensorThresholds(
                    profile.temperatureMinC(),
                    profile.temperatureMaxC());
            case HUMIDITY -> new SensorThresholds(
                    profile.humidityMinPct(),
                    profile.humidityMaxPct());
            case SOIL_MOISTURE -> new SensorThresholds(
                    profile.soilMoistureMinPct(),
                    profile.soilMoistureMaxPct());
            // 조도는 순간값으로 판정하지 않는다. 밤에는 0 lux가 정상이며 생육 기준은
            // 일일 누적 광량(daily_light_*)과 일조 시간(photoperiod_hours)으로 표현되어 있다.
            // 하루가 마감된 뒤 누적값으로 판정하는 기능은 별도로 구현한다.
            case ILLUMINANCE -> NONE;
        };
    }

    public boolean judgeable() {
        return min != null && max != null;
    }

    public SensorStatus evaluate(BigDecimal value) {
        if (!judgeable()) {
            return SensorStatus.NOT_APPLICABLE;
        }
        if (value.compareTo(min) < 0) {
            return SensorStatus.LOW;
        }
        if (value.compareTo(max) > 0) {
            return SensorStatus.HIGH;
        }
        return SensorStatus.NORMAL;
    }
}
