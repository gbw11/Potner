package com.potner.mqtt.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 서버가 로봇에 보내는 명령의 발행 설정이다.
 *
 * <p>수신 설정({@link MqttProperties})과 나눠 둔 이유는 클라이언트 ID 가 달라야 하기 때문이다.
 * 아래 {@code clientIdSuffix} 주석에 그 이유를 적었다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.mqtt.command")
public record MqttCommandProperties(

        /**
         * 명령 토픽 서식이다. {@code {deviceUid}} 와 {@code {command}} 를 치환한다.
         *
         * <p>수신 토픽이 {@code potner/device/+/sensor/telemetry} 이므로 발행도 같은 접두사를
         * 쓰고 {@code command/} 아래로 모은다. 하드웨어팀이 토픽을 바꾸면 이미지를 다시 만들지
         * 않고 환경변수로 맞출 수 있다.
         */
        @NotBlank String topicTemplate,

        /**
         * 구독용 클라이언트 ID 에 붙일 접미사다.
         *
         * <p>같은 ID 로 두 클라이언트가 붙으면 브로커가 앞선 세션을 끊는다. 구독과 발행이 같은
         * ID 를 쓰면 서로를 끊으며 무한 재접속에 빠져 센서 수집까지 멈춘다.
         */
        @NotBlank String clientIdSuffix,

        @Min(0) @Max(2) int qos
) {

    private static final String DEVICE_UID_PLACEHOLDER = "{deviceUid}";
    private static final String COMMAND_PLACEHOLDER = "{command}";

    @AssertTrue(message = "potner.mqtt.command.topic-template must contain {deviceUid} and {command}")
    public boolean isTopicTemplateValid() {
        return topicTemplate != null
                && topicTemplate.contains(DEVICE_UID_PLACEHOLDER)
                && topicTemplate.contains(COMMAND_PLACEHOLDER);
    }

    /** 장치와 명령 이름을 넣어 실제 토픽을 만든다. */
    public String topicFor(String deviceUid, String command) {
        return topicTemplate
                .replace(DEVICE_UID_PLACEHOLDER, deviceUid)
                .replace(COMMAND_PLACEHOLDER, command);
    }

    /** 구독 클라이언트와 겹치지 않는 발행용 클라이언트 ID 다. */
    public String publisherClientId(String subscriberClientId) {
        return subscriberClientId + clientIdSuffix;
    }
}
