package com.potner.mqtt.config;

import com.potner.mqtt.application.MqttMessageSubscriber;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;

import static org.assertj.core.api.Assertions.assertThat;

class MqttConfigurationTest {

    @Test
    void mqttBeansAreNotCreatedWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(MqttConfiguration.class, MqttMessageSubscriber.class)
                .withPropertyValues("potner.mqtt.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MqttProperties.class);
                    assertThat(context).doesNotHaveBean(MqttPahoClientFactory.class);
                    assertThat(context).doesNotHaveBean(MqttPahoMessageDrivenChannelAdapter.class);
                    assertThat(context).doesNotHaveBean(MqttMessageSubscriber.class);
                });
    }

    @Test
    void mqttPropertiesAreBound() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "potner.mqtt.enabled=true",
                        "potner.mqtt.broker-url=tcp://mosquitto:1883",
                        "potner.mqtt.username=potner-server",
                        "potner.mqtt.password=test-password",
                        "potner.mqtt.client-id=potner-backend-prod",
                        "potner.mqtt.topic=potner/device/+/sensor/#",
                        "potner.mqtt.qos=1",
                        "potner.mqtt.connection-timeout-seconds=15",
                        "potner.mqtt.keep-alive-seconds=45",
                        "potner.mqtt.recovery-interval-ms=5000"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MqttProperties.class);
                    MqttProperties properties = context.getBean(MqttProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.brokerUrl()).isEqualTo("tcp://mosquitto:1883");
                    assertThat(properties.username()).isEqualTo("potner-server");
                    assertThat(properties.password()).isEqualTo("test-password");
                    assertThat(properties.clientId()).isEqualTo("potner-backend-prod");
                    assertThat(properties.topic()).isEqualTo("potner/device/+/sensor/#");
                    assertThat(properties.qos()).isEqualTo(1);
                    assertThat(properties.connectionTimeoutSeconds()).isEqualTo(15);
                    assertThat(properties.keepAliveSeconds()).isEqualTo(45);
                    assertThat(properties.recoveryIntervalMs()).isEqualTo(5000);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MqttProperties.class)
    static class PropertiesConfiguration {
    }
}
