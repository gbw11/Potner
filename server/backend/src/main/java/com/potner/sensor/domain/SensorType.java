package com.potner.sensor.domain;

public enum SensorType {
    TEMPERATURE(SensorUnit.CELSIUS),
    HUMIDITY(SensorUnit.PERCENT),
    SOIL_MOISTURE(SensorUnit.PERCENT),
    ILLUMINANCE(SensorUnit.LUX);

    private final SensorUnit unit;

    SensorType(SensorUnit unit) {
        this.unit = unit;
    }

    /** 측정값이 없어도 응답에 단위를 채울 수 있도록 센서 종류별 단위를 노출한다. */
    public SensorUnit unit() {
        return unit;
    }
}
