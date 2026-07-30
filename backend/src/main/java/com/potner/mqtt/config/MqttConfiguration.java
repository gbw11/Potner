package com.potner.mqtt.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "potner.mqtt", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({
        MqttProperties.class,
        MqttHeartbeatProperties.class,
        MqttRobotStateProperties.class,
        MqttBatteryProperties.class,
        MqttCommandProperties.class,
        MqttCommandResultProperties.class,
        MqttWaterLowProperties.class
})
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
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    /**
     * 서버가 로봇에 명령을 발행하는 핸들러다.
     *
     * <p>{@code @ServiceActivator} 로 선언해야 Spring Integration 이 이 핸들러를 소비자로 감싸
     * 컨텍스트 시작 시점에 브로커에 연결한다. {@code MqttPahoMessageHandler} 를 그냥 빈으로만
     * 두면 {@code Lifecycle} 이 시작되지 않아 첫 발행에서 실패한다.
     *
     * <p>기본 토픽을 두지 않는다. 토픽은 매 메시지의 {@code MqttHeaders.TOPIC} 헤더로 온다.
     * 기본값이 있으면 헤더를 빠뜨렸을 때 엉뚱한 토픽으로 조용히 나간다.
     *
     * <p>{@code setAsync(true)} 라서 발행이 브로커 응답을 기다리지 않는다. 호출자가 주기
     * 스케줄러라 응답을 기다리면 표정 갱신이 브로커 지연만큼 밀린다.
     */
    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutboundHandler(
            MqttProperties properties,
            MqttCommandProperties commandProperties,
            MqttPahoClientFactory mqttClientFactory
    ) {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(
                commandProperties.publisherClientId(properties.clientId()),
                mqttClientFactory
        );
        handler.setAsync(true);
        handler.setDefaultQos(commandProperties.qos());

        log.info(
                "MQTT publisher configured: clientId={}, topicTemplate={}, qos={}",
                commandProperties.publisherClientId(properties.clientId()),
                commandProperties.topicTemplate(),
                commandProperties.qos()
        );
        return handler;
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter(
            MqttProperties properties,
            MqttHeartbeatProperties heartbeatProperties,
            MqttRobotStateProperties robotStateProperties,
            MqttBatteryProperties batteryProperties,
            MqttCommandResultProperties commandResultProperties,
            MqttWaterLowProperties waterLowProperties,
            MqttPahoClientFactory mqttClientFactory,
            MessageChannel mqttInputChannel
    ) {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                properties.clientId(),
                mqttClientFactory,
                properties.topic(),
                heartbeatProperties.topic(),
                robotStateProperties.topic(),
                batteryProperties.topic(),
                commandResultProperties.topic(),
                waterLowProperties.topic()
        );
        // 값을 하나만 준다. 토픽 수만큼 넘기는 형태였는데, 토픽을 추가할 때마다 인자를 늘려야
        // 하고 빠뜨리면 개수 불일치로 기동이 실패한다. 하나면 모든 토픽에 같은 QoS 가 걸린다.
        adapter.setQos(properties.qos());
        adapter.setOutputChannel(mqttInputChannel);

        log.info(
                "MQTT subscriber configured: broker={}, clientId={}, sensorTopic={}, "
                        + "heartbeatTopic={}, robotStateTopic={}, batteryTopic={}, "
                        + "commandResultTopic={}, waterLowTopic={}, qos={}",
                properties.brokerUrl(),
                properties.clientId(),
                properties.topic(),
                heartbeatProperties.topic(),
                robotStateProperties.topic(),
                batteryProperties.topic(),
                commandResultProperties.topic(),
                waterLowProperties.topic(),
                properties.qos()
        );
        return adapter;
    }
}
