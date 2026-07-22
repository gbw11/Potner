package com.potner.mqtt.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.GenericMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class MqttMessageSubscriberTest {

    private final MqttMessageSubscriber subscriber = new MqttMessageSubscriber();

    @Test
    void logsTopicAndRawStringPayload(CapturedOutput output) {
        Message<String> message = new GenericMessage<>(
                "{\"temperature\":24.3}",
                Map.of(MqttHeaders.RECEIVED_TOPIC, "potner/device/raspberry-01/sensor/environment")
        );

        subscriber.receive(message);

        assertThat(output).contains("topic=potner/device/raspberry-01/sensor/environment");
        assertThat(output).contains("payload={\"temperature\":24.3}");
    }

    @Test
    void decodesByteArrayPayloadAsUtf8(CapturedOutput output) {
        Message<byte[]> message = new GenericMessage<>(
                "soil=52".getBytes(StandardCharsets.UTF_8),
                Map.of(MqttHeaders.RECEIVED_TOPIC, "potner/device/raspberry-01/sensor/soil")
        );

        subscriber.receive(message);

        assertThat(output).contains("payload=soil=52");
    }

    @Test
    void safelyIgnoresMissingTopicAndNullPayload() {
        Message<Object> missingTopic = new GenericMessage<>("payload");
        Message<Object> nullPayload = new Message<>() {
            @Override
            public Object getPayload() {
                return null;
            }

            @Override
            public MessageHeaders getHeaders() {
                return new MessageHeaders(Map.of(
                        MqttHeaders.RECEIVED_TOPIC,
                        "potner/device/test/sensor/value"
                ));
            }
        };

        assertThatCode(() -> subscriber.receive(missingTopic)).doesNotThrowAnyException();
        assertThatCode(() -> subscriber.receive(nullPayload)).doesNotThrowAnyException();
    }
}
