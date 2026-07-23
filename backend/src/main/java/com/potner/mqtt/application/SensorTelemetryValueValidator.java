package com.potner.mqtt.application;

import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SensorTelemetryValueValidator {

    private static final BigDecimal TEMPERATURE_MIN = new BigDecimal("-40");
    private static final BigDecimal TEMPERATURE_MAX = new BigDecimal("85");
    private static final BigDecimal PERCENT_MIN = BigDecimal.ZERO;
    private static final BigDecimal PERCENT_MAX = new BigDecimal("100");

    public boolean isValid(SensorTelemetryMessage message) {
        return switch (message.sensorType()) {
            case TEMPERATURE -> message.unit() == SensorUnit.CELSIUS
                    && isBetween(message.value(), TEMPERATURE_MIN, TEMPERATURE_MAX);
            case HUMIDITY, SOIL_MOISTURE -> message.unit() == SensorUnit.PERCENT
                    && isBetween(message.value(), PERCENT_MIN, PERCENT_MAX);
            case ILLUMINANCE -> message.unit() == SensorUnit.LUX
                    && message.value().compareTo(BigDecimal.ZERO) >= 0;
        };
    }

    private boolean isBetween(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
