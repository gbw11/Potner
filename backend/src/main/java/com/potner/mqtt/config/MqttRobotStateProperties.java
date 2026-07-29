package com.potner.mqtt.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 로봇 상태 보고 구독 설정이다.
 *
 * <p>heartbeat 와 나눠 둔다. 두 토픽이 같은 {@code status/} 아래에 있지만 보내는 장치와 주기가
 * 다르고, 하드웨어팀이 한쪽 이름을 바꿀 때 다른 쪽을 건드리지 않아야 한다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.mqtt.robot-state")
public record MqttRobotStateProperties(
        @NotBlank String topic
) {
}
