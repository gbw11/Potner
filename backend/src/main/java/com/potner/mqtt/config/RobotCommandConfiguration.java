package com.potner.mqtt.config;

import com.potner.mqtt.application.LoggingRobotCommandPublisher;
import com.potner.mqtt.application.MqttRobotCommandPublisher;
import com.potner.mqtt.application.RobotCommandPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;
import tools.jackson.databind.ObjectMapper;

/**
 * 명령 발행기 구현을 고른다.
 *
 * <p>{@link MqttConfiguration} 자체가 {@code potner.mqtt.enabled} 로 조건부라서 브로커가 꺼져
 * 있으면 발행 채널 빈이 아예 없다. 그 상태에서도 {@link RobotCommandPublisher} 를 주입받는
 * 쪽이 떠야 하므로 여기서 갈라 준다.
 *
 * <p>두 빈의 조건이 서로 배타적이다. {@code @ConditionalOnMissingBean} 으로 순서에 기대면
 * 같은 설정 클래스 안에서 선언 순서에 따라 결과가 달라질 수 있어 쓰지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class RobotCommandConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "potner.mqtt", name = "enabled", havingValue = "true")
    public RobotCommandPublisher mqttRobotCommandPublisher(
            @Qualifier("mqttOutboundChannel") MessageChannel mqttOutboundChannel,
            MqttCommandProperties commandProperties,
            ObjectMapper objectMapper
    ) {
        return new MqttRobotCommandPublisher(mqttOutboundChannel, commandProperties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "potner.mqtt",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public RobotCommandPublisher loggingRobotCommandPublisher() {
        return new LoggingRobotCommandPublisher();
    }
}
