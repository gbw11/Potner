package com.potner.mqtt.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 물 부족 보고 구독 설정이다. {@link MqttBatteryProperties} 처럼 토픽만 담는다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.mqtt.water-low")
public record MqttWaterLowProperties(
        @NotBlank String topic
) {
}
