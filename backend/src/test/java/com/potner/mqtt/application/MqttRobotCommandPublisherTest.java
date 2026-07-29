package com.potner.mqtt.application;

import com.potner.mqtt.config.MqttCommandProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqttRobotCommandPublisherTest {

    private static final MqttCommandProperties PROPERTIES = new MqttCommandProperties(
            "potner/device/{deviceUid}/command/{command}",
            "-command",
            1
    );

    @Mock
    private MessageChannel outboundChannel;

    private MqttRobotCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new MqttRobotCommandPublisher(outboundChannel, PROPERTIES, new ObjectMapper());
    }

    @Test
    void topicCarriesTheDeviceAndTheCommandName() {
        when(outboundChannel.send(any())).thenReturn(true);

        publisher.publish("jetson-01", RobotCommand.expression(new Payload("HAPPY", "SUNLIGHT")));

        Message<?> sent = capture();
        assertThat(sent.getHeaders().get(MqttHeaders.TOPIC))
                .isEqualTo("potner/device/jetson-01/command/expression");
        assertThat(sent.getHeaders().get(MqttHeaders.QOS)).isEqualTo(1);
    }

    @Test
    void payloadIsSerializedWithTheProjectsJacksonSoFieldNamesSurvive() {
        // com.fasterxml 쪽 ObjectMapper 를 주입하면 컴파일은 되지만 빈이 없어 컨텍스트가 터진다.
        // firebase-admin 이 Jackson 2 를 함께 끌고 와서 생기는 함정이다.
        when(outboundChannel.send(any())).thenReturn(true);

        publisher.publish("jetson-01", RobotCommand.expression(new Payload("VERY_HAPPY", "WATERING")));

        assertThat(capture().getPayload())
                .isEqualTo("{\"expression\":\"VERY_HAPPY\",\"reason\":\"WATERING\"}");
    }

    @Test
    void brokerFailureDoesNotEscapeToTheCaller() {
        // 호출자가 주기 스케줄러다. 예외가 올라가면 다음 회차까지 표정 갱신이 멈춘다.
        when(outboundChannel.send(any()))
                .thenThrow(new MessageDeliveryException("broker is down"));

        assertThatCode(() -> publisher.publish("jetson-01", RobotCommand.expression(new Payload("SAD", "TEMPERATURE"))))
                .doesNotThrowAnyException();
    }

    private Message<?> capture() {
        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(outboundChannel).send(captor.capture());
        return captor.getValue();
    }

    private record Payload(String expression, String reason) {
    }
}
