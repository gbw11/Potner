package com.potner.light.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 일일 광량 집계는 MQTT 수신 여부와 무관하게 동작해야 하므로 스케줄링을 별도로 활성화한다.
 * 기존 {@code @EnableScheduling}은 MQTT 설정에 붙어 있어 MQTT를 끄면 함께 꺼진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DailyLightProperties.class)
@EnableScheduling
public class DailyLightConfiguration {
}
