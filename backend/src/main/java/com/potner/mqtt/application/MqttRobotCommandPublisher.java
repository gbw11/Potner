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

    /**
     * {@inheritDoc}
     *
     * <p>성공을 {@code info} 로 남긴다. 명령이 나갔는지 확인할 다른 수단이 없기 때문이다.
     * 표정은 DB 에 저장되지 않으므로 이 로그가 유일한 흔적이고, 로봇 하나당 30초에 한 줄이라
     * 양도 감당된다.
     *
     * <p><strong>이 로그는 "서버가 보냈다" 까지만 증명한다.</strong> 브로커가 받았는지도, 로봇이
     * 받았는지도 말해 주지 않는다. MQTT 3.1.1 에는 거부 이유 코드가 없어서, ACL 에 write 권한이
     * 없으면 브로커가 조용히 버리고 PUBACK 은 그대로 보낸다. 즉 권한 문제는 클라이언트 쪽에서
     * 감지할 수 없다. ACL 이 맞는지는 mosquitto 쪽에서 따로 확인해야 한다.
     */
    @Override
    public void publish(String deviceUid, RobotCommand command) {
        String topic = properties.topicFor(deviceUid, command.name());
        try {
            outboundChannel.send(MessageBuilder
                    .withPayload(objectMapper.writeValueAsString(command.payload()))
                    .setHeader(MqttHeaders.TOPIC, topic)
                    .setHeader(MqttHeaders.QOS, properties.qos())
                    .build());
            // 페이로드를 함께 남긴다. 표정이 실제로 바뀌고 있는지는 값을 봐야 알 수 있고,
            // 지금 나가는 것은 표정뿐이라 한 줄에 들어간다. 큰 페이로드를 보내는 명령이
            // 생기면 그때 줄여야 한다.
            log.info("Robot command published: topic={}, payload={}", topic, command.payload());
        } catch (RuntimeException exception) {
            // 브로커가 끊겼거나 직렬화가 실패했다. 주기 발행이라 다음 회차에 다시 나간다.
            log.warn("Robot command publish failed: topic={}", topic, exception);
        }
    }
}
