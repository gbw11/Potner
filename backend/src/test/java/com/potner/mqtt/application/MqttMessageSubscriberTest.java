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
    private static final String HEARTBEAT_TOPIC =
            "potner/device/jetson-01/status/heartbeat";

    private SensorTelemetryMessageProcessor sensorProcessor;
    private HeartbeatMessageProcessor heartbeatProcessor;
    private RobotStateMessageProcessor robotStateProcessor;
    private MqttMessageSubscriber subscriber;

    @BeforeEach
    void setUp() {
        sensorProcessor = mock(SensorTelemetryMessageProcessor.class);
        heartbeatProcessor = mock(HeartbeatMessageProcessor.class);
        robotStateProcessor = mock(RobotStateMessageProcessor.class);
        subscriber = new MqttMessageSubscriber(
                sensorProcessor,
                heartbeatProcessor,
                robotStateProcessor
        );
    }

    @Test
    void delegatesTopicAndRawStringPayload() {
        Message<String> message = new GenericMessage<>(
                "{\"temperatureC\":24.3}",
                Map.of(MqttHeaders.RECEIVED_TOPIC, TOPIC)
        );

        subscriber.receive(message);

        verify(sensorProcessor).process(TOPIC, "{\"temperatureC\":24.3}");
        verify(heartbeatProcessor, never()).process(TOPIC, "{\"temperatureC\":24.3}");
    }

    @Test
    void decodesByteArrayPayloadAsUtf8() {
        Message<byte[]> message = new GenericMessage<>(
                "soil=52".getBytes(StandardCharsets.UTF_8),
                Map.of(MqttHeaders.RECEIVED_TOPIC, TOPIC)
        );

        subscriber.receive(message);

        verify(sensorProcessor).process(TOPIC, "soil=52");
    }

    @Test
    void delegatesHeartbeatTopicToHeartbeatProcessor() {
        Message<String> message = new GenericMessage<>(
                "{\"deviceId\":\"jetson-01\"}",
                Map.of(MqttHeaders.RECEIVED_TOPIC, HEARTBEAT_TOPIC)
        );

        subscriber.receive(message);

        verify(heartbeatProcessor).process(
                HEARTBEAT_TOPIC,
                "{\"deviceId\":\"jetson-01\"}"
        );
        verify(sensorProcessor, never()).process(
                HEARTBEAT_TOPIC,
                "{\"deviceId\":\"jetson-01\"}"
        );
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
        verify(sensorProcessor, never()).process(TOPIC, "payload");
        verify(heartbeatProcessor, never()).process(TOPIC, "payload");
    }

    @Test
    void continuesAfterUnexpectedProcessingFailure() {
        doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(sensorProcessor).process(TOPIC, "payload");
        Message<String> message = new GenericMessage<>(
                "payload",
                Map.of(MqttHeaders.RECEIVED_TOPIC, TOPIC)
        );

        assertThatCode(() -> subscriber.receive(message)).doesNotThrowAnyException();
        assertThatCode(() -> subscriber.receive(message)).doesNotThrowAnyException();

        verify(sensorProcessor, org.mockito.Mockito.times(2)).process(TOPIC, "payload");
    }
}
