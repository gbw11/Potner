package com.potner.mqtt.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 배터리 잔량 보고 구독 설정이다.
 *
 * <p>토픽만 담는다. "어느 장치의 배터리를 믿을지" 는 전송 수단과 무관한 도메인 정책이라
 * {@code potner.device.battery-reporter-type} 으로 뺐다. 이 설정은
 * {@code potner.mqtt.enabled=true} 일 때만 로드되므로, 브로커를 끈 환경에서도 살아 있어야 하는
 * 도메인 서비스가 여기 있는 값을 주입받으면 컨텍스트가 아예 뜨지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.mqtt.battery")
public record MqttBatteryProperties(
        @NotBlank String topic
) {
}
