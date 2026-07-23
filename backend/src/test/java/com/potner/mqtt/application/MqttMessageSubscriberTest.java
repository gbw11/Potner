package com.potner.mqtt.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.GenericMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MqttMessageSubscriberTest {

    private static final String TOPIC = "potner/device/raspberry-01/sensor/telemetry";

    private SensorTelemetryMessageProcessor processor;
    private MqttMessageSubscriber subscriber;

    @BeforeEach
    void setUp() {
        processor = mock(SensorTelemetryMessageProcessor.class);
        subscriber = new MqttMessageSubscriber(processor);
    }

    @Test
    void delegatesTopicAndRawStringPayload() {
        Message<String> message = new GenericMessage<>(
                "{\"temperatureC\":24.3}",
                Map.of(MqttHeaders.RECEIVED_TOPIC, TOPIC)
        );

        subscriber.receive(message);

        verify(processor).process(TOPIC, "{\"temperatureC\":24.3}");
    }

    @Test
    void decodesByteArrayPayloadAsUtf8() {
        Message<byte[]> message = new GenericMessage<>(
                "soil=52".getBytes(StandardCharsets.UTF_8),
                Map.of(MqttHeaders.RECEIVED_TOPIC, TOPIC)
        );

        subscriber.receive(message);

        verify(processor).process(TOPIC, "soil=52");
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
                return new MessageHeaders(Map.of(MqttHeaders.RECEIVED_TOPIC, TOPIC));
            }
        };

        assertThatCode(() -> subscriber.receive(missingTopic)).doesNotThrowAnyException();
        assertThatCode(() -> subscriber.receive(nullPayload)).doesNotThrowAnyException();
        verify(processor, never()).process(TOPIC, "payload");
    }

    @Test
    void continuesAfterUnexpectedProcessingFailure() {
        doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(processor).process(TOPIC, "payload");
        Message<String> message = new GenericMessage<>(
                "payload",
                Map.of(MqttHeaders.RECEIVED_TOPIC, TOPIC)
        );

        assertThatCode(() -> subscriber.receive(message)).doesNotThrowAnyException();
        assertThatCode(() -> subscriber.receive(message)).doesNotThrowAnyException();

        verify(processor, org.mockito.Mockito.times(2)).process(TOPIC, "payload");
    }
}
