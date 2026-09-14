package com.potner.alert.domain;

import com.potner.sensor.domain.SensorType;

import java.util.Optional;

/**
 * 알림을 발생시키는 판정 지표다.
 *
 * <p>센서 종류와 1:1이 아니다. 조도는 순간값으로 판정하지 않고 하루 누적 광량과 일조 시간으로
 * 판정하므로 {@code DAILY_LIGHT}, {@code PHOTOPERIOD}가 별도 지표로 존재한다.
 *
 * <p>{@code STATION_WATER_LOW} 는 측정값이 없는 유일한 지표다. 물 부족 보고가 불리언이라
 * {@code measured_value}·{@code threshold_min}·{@code threshold_max} 가 NULL 로 남는다.
 * 방향은 항상 {@code LOW} 다.
 *
 * <p>{@code DRAINAGE_TRAY} 는 센서가 아니라 누적 급수량으로 판정한다. 측정값(누적 ml)과
 * 기준(임계 ml)이 실제로 있으므로 세 값을 모두 채운다. 방향은 항상 {@code HIGH} 다 —
 * 트레이는 넘칠 때만 문제가 된다.
 */
public enum AlertMetricType {

    TEMPERATURE,
    HUMIDITY,
    SOIL_MOISTURE,
    DAILY_LIGHT,
    PHOTOPERIOD,
    STATION_WATER_LOW,
    DRAINAGE_TRAY;

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
