package com.potner.mqtt.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "potner.mqtt", name = "enabled", havingValue = "true")
public class MqttMessageSubscriber {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageSubscriber.class);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;
    private static final String SENSOR_TOPIC_SUFFIX = "/sensor/telemetry";
    private static final String HEARTBEAT_TOPIC_SUFFIX = "/status/heartbeat";
    private static final String ROBOT_STATE_TOPIC_SUFFIX = "/status/state";
    private static final String BATTERY_TOPIC_SUFFIX = "/status/battery";

    private final SensorTelemetryMessageProcessor sensorProcessor;
    private final HeartbeatMessageProcessor heartbeatProcessor;
    private final RobotStateMessageProcessor robotStateProcessor;
    private final BatteryMessageProcessor batteryProcessor;

    public MqttMessageSubscriber(
            SensorTelemetryMessageProcessor sensorProcessor,
            HeartbeatMessageProcessor heartbeatProcessor,
            RobotStateMessageProcessor robotStateProcessor,
            BatteryMessageProcessor batteryProcessor
    ) {
        this.sensorProcessor = sensorProcessor;
        this.heartbeatProcessor = heartbeatProcessor;
        this.robotStateProcessor = robotStateProcessor;
        this.batteryProcessor = batteryProcessor;
    }

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void receive(Message<?> message) {
        String topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC, String.class);
        Object payload = message.getPayload();

        if (topic == null || topic.isBlank()) {
            log.warn("MQTT message ignored because the received topic is missing");
            return;
        }
        if (payload == null) {
            log.warn("MQTT message ignored because the payload is null: topic={}", topic);
            return;
        }

        String rawPayload = payload instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : payload.toString();
        log.debug("MQTT message received: topic={}, payload={}", topic, summarize(rawPayload));

        try {
            if (topic.endsWith(SENSOR_TOPIC_SUFFIX)) {
                sensorProcessor.process(topic, rawPayload);
            } else if (topic.endsWith(HEARTBEAT_TOPIC_SUFFIX)) {
                heartbeatProcessor.process(topic, rawPayload);
            } else if (topic.endsWith(ROBOT_STATE_TOPIC_SUFFIX)) {
                robotStateProcessor.process(topic, rawPayload);
            } else if (topic.endsWith(BATTERY_TOPIC_SUFFIX)) {
                batteryProcessor.process(topic, rawPayload);
            } else {
                log.warn("MQTT message ignored because the topic is unsupported: topic={}", topic);
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Unexpected MQTT message processing failure: topic={}, error={}",
                    topic,
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }

    private String summarize(String payload) {
        String singleLine = payload.replace("\r", "\\r").replace("\n", "\\n");
        if (singleLine.length() <= MAX_LOG_PAYLOAD_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...";
    }
}
