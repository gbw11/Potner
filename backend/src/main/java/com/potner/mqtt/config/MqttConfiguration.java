package com.potner.mqtt.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "potner.mqtt", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({MqttProperties.class, MqttHeartbeatProperties.class})
@EnableScheduling
public class MqttConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MqttConfiguration.class);

    @Bean
    public MqttPahoClientFactory mqttClientFactory(MqttProperties properties) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{properties.brokerUrl()});
        options.setUserName(properties.username());
        options.setPassword(properties.password().toCharArray());
        options.setAutomaticReconnect(true);
        options.setMaxReconnectDelay(Math.toIntExact(properties.recoveryIntervalMs()));
        options.setConnectionTimeout(properties.connectionTimeoutSeconds());
        options.setKeepAliveInterval(properties.keepAliveSeconds());
        options.setCleanSession(true);

        DefaultMqttPahoClientFactory clientFactory = new DefaultMqttPahoClientFactory();
        clientFactory.setConnectionOptions(options);
        return clientFactory;
    }

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter(
            MqttProperties properties,
            MqttHeartbeatProperties heartbeatProperties,
            MqttPahoClientFactory mqttClientFactory,
            MessageChannel mqttInputChannel
    ) {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                properties.clientId(),
                mqttClientFactory,
                properties.topic(),
                heartbeatProperties.topic()
        );
        adapter.setQos(properties.qos(), properties.qos());
        adapter.setOutputChannel(mqttInputChannel);

        log.info(
                "MQTT subscriber configured: broker={}, clientId={}, sensorTopic={}, "
                        + "heartbeatTopic={}, qos={}",
                properties.brokerUrl(),
                properties.clientId(),
                properties.topic(),
                heartbeatProperties.topic(),
                properties.qos()
        );
        return adapter;
    }
}
