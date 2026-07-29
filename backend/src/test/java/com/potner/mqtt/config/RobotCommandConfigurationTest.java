package com.potner.mqtt.config;

import com.potner.mqtt.application.LoggingRobotCommandPublisher;
import com.potner.mqtt.application.MqttRobotCommandPublisher;
import com.potner.mqtt.application.RobotCommandPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageChannel;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RobotCommandConfigurationTest {

    @Test
    void mqttPublisherIsUsedWhenTheBrokerIsEnabled() {
        runner().withPropertyValues("potner.mqtt.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(RobotCommandPublisher.class);
            assertThat(context.getBean(RobotCommandPublisher.class))
                    .isInstanceOf(MqttRobotCommandPublisher.class);
        });
    }

    @Test
    void noOpPublisherIsUsedWhenTheBrokerIsDisabled() {
        // 로컬 개발과 CI 임시 컨테이너에는 브로커가 없다. 여기서 빈이 만들어지지 않으면
        // 발행기를 주입받는 쪽이 뜨지 못해 Health Check 가 실패하고 배포가 막힌다.
        runner().withPropertyValues("potner.mqtt.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(RobotCommandPublisher.class);
            assertThat(context.getBean(RobotCommandPublisher.class))
                    .isInstanceOf(LoggingRobotCommandPublisher.class);
        });
    }

    @Test
    void noOpPublisherIsUsedWhenThePropertyIsMissingEntirely() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(RobotCommandPublisher.class);
            assertThat(context.getBean(RobotCommandPublisher.class))
                    .isInstanceOf(LoggingRobotCommandPublisher.class);
        });
    }

    /**
     * MQTT 설정 전체를 띄우지 않는다. {@code MqttPahoMessageHandler} 는 컨텍스트가 시작될 때
     * 브로커에 연결을 시도하므로, 발행기 선택만 확인하려면 의존성을 대신 채워야 한다.
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RobotCommandConfiguration.class, StubDependencies.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class StubDependencies {

        @Bean
        public MessageChannel mqttOutboundChannel() {
            return new DirectChannel();
        }

        @Bean
        public MqttCommandProperties mqttCommandProperties() {
            return new MqttCommandProperties(
                    "potner/device/{deviceUid}/command/{command}",
                    "-command",
                    1
            );
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
