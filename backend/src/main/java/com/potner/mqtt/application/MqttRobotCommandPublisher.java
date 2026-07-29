package com.potner.mqtt.application;

import com.potner.mqtt.config.MqttCommandProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * 명령을 MQTT 로 발행한다.
 *
 * <p>{@code ObjectMapper} 는 반드시 {@code tools.jackson.databind} 것이어야 한다. 이 프로젝트는
 * Jackson 3 을 쓰는데 firebase-admin 이 Jackson 2 를 함께 끌고 오므로 {@code
 * com.fasterxml.jackson.databind.ObjectMapper} 도 컴파일은 된다. 그러나 그 타입의 빈은 없어서
 * 컨텍스트가 뜰 때 터진다. 실제로 한 번 겪었다.
 */
public class MqttRobotCommandPublisher implements RobotCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttRobotCommandPublisher.class);

    private final MessageChannel outboundChannel;
    private final MqttCommandProperties properties;
    private final ObjectMapper objectMapper;

    public MqttRobotCommandPublisher(
            MessageChannel outboundChannel,
            MqttCommandProperties properties,
            ObjectMapper objectMapper
    ) {
        this.outboundChannel = outboundChannel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String deviceUid, RobotCommand command) {
        String topic = properties.topicFor(deviceUid, command.name());
        try {
            outboundChannel.send(MessageBuilder
                    .withPayload(objectMapper.writeValueAsString(command.payload()))
                    .setHeader(MqttHeaders.TOPIC, topic)
                    .setHeader(MqttHeaders.QOS, properties.qos())
                    .build());
            log.debug("Robot command published: topic={}", topic);
        } catch (RuntimeException exception) {
            // 브로커가 끊겼거나 직렬화가 실패했다. 주기 발행이라 다음 회차에 다시 나간다.
            log.warn("Robot command publish failed: topic={}", topic, exception);
        }
    }
}
