package com.potner.happiness.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 표정 판정과 발행 설정이다.
 *
 * <p>{@code @EnableScheduling} 을 여기도 붙인다. {@code MqttConfiguration} 이 이미 켜지만 그쪽은
 * {@code potner.mqtt.enabled} 로 조건부라, 브로커를 끈 환경에서 주기 작업이 조용히 멈춘다.
 * 발행은 no-op 으로 떨어져도 판정은 돌아야 로그로 확인할 수 있다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HappinessProperties.class)
@EnableScheduling
public class HappinessConfiguration {
}
