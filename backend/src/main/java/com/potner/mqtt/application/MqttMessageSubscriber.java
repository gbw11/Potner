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
        log.info("MQTT message received. topic={}, payload={}", topic, rawPayload);
    }
}
