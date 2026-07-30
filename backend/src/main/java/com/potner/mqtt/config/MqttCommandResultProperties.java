package com.potner.mqtt.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 명령 결과 회신 구독 설정이다.
 *
 * <p>와일드카드({@code result/#})로 받는다. 명령 종류(water/capture)가 늘 때마다 구독 토픽을
 * 추가하면 브로커 재접속이 필요한데, 어차피 어떤 결과가 왔는지는 토픽 파서가 가린다.
 * 브로커 ACL 도 {@code potner/device/+/result/#} 읽기를 이미 허용하고 있다.
 *
 * <p>{@link MqttBatteryProperties} 처럼 토픽만 담는다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.mqtt.command-result")
public record MqttCommandResultProperties(
        @NotBlank String topic
) {
}
