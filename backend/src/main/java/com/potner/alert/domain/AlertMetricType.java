package com.potner.alert.domain;

import com.potner.sensor.domain.SensorType;

import java.util.Optional;

/**
 * 알림을 발생시키는 판정 지표다.
 *
 * <p>센서 종류와 1:1이 아니다. 조도는 순간값으로 판정하지 않고 하루 누적 광량과 일조 시간으로
 * 판정하므로 {@code DAILY_LIGHT}, {@code PHOTOPERIOD}가 별도 지표로 존재한다.
 */
public enum AlertMetricType {

    TEMPERATURE,
    HUMIDITY,
    SOIL_MOISTURE,
    DAILY_LIGHT,
    PHOTOPERIOD;

    /**
     * 순간값으로 판정할 수 있는 센서 종류를 지표로 변환한다.
     * 조도는 대응 지표가 없으므로 빈 값을 돌려준다.
     */
    public static Optional<AlertMetricType> ofInstantSensor(SensorType sensorType) {
        return switch (sensorType) {
            case TEMPERATURE -> Optional.of(TEMPERATURE);
            case HUMIDITY -> Optional.of(HUMIDITY);
            case SOIL_MOISTURE -> Optional.of(SOIL_MOISTURE);
            case ILLUMINANCE -> Optional.empty();
        };
    }
}
