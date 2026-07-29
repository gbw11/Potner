package com.potner.mqtt.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqttCommandPropertiesTest {

    private static final String TEMPLATE = "potner/device/{deviceUid}/command/{command}";

    @Test
    void bothPlaceholdersAreReplaced() {
        MqttCommandProperties properties = properties(TEMPLATE);

        assertThat(properties.topicFor("jetson-01", "expression"))
                .isEqualTo("potner/device/jetson-01/command/expression");
        assertThat(properties.topicFor("raspberry-01", "watering"))
                .isEqualTo("potner/device/raspberry-01/command/watering");
    }

    @Test
    void publisherClientIdDiffersFromTheSubscriberId() {
        // 같으면 브로커가 앞선 세션을 끊는다. 구독과 발행이 서로를 끊으며 무한 재접속에 빠진다.
        MqttCommandProperties properties = properties(TEMPLATE);

        assertThat(properties.publisherClientId("potner-backend-prod"))
                .isEqualTo("potner-backend-prod-command")
                .isNotEqualTo("potner-backend-prod");
    }

    @Test
    void templateMissingAPlaceholderIsRejected() {
        // 치환이 안 되면 모든 장치가 같은 토픽을 쓰거나 '{deviceUid}' 가 그대로 토픽에 박힌다.
        assertThat(properties(TEMPLATE).isTopicTemplateValid()).isTrue();
        assertThat(properties("potner/device/{deviceUid}/command").isTopicTemplateValid()).isFalse();
        assertThat(properties("potner/device/command/{command}").isTopicTemplateValid()).isFalse();
        assertThat(properties("potner/device/command/expression").isTopicTemplateValid()).isFalse();
    }

    private MqttCommandProperties properties(String topicTemplate) {
        return new MqttCommandProperties(topicTemplate, "-command", 1);
    }
}
