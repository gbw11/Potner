package com.potner.device.config;

import com.potner.device.application.RobotBatteryService;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 장치 도메인 서비스가 브로커 설정 없이도 만들어지는지 본다.
 *
 * <p>{@code potner.mqtt.*} 프로퍼티는 {@code MqttConfiguration} 이 등록하는데 그 설정은
 * {@code potner.mqtt.enabled=true} 일 때만 로드된다. 브로커를 끈 환경에서도 살아 있어야 하는
 * 서비스가 그 프로퍼티를 주입받으면 컨텍스트가 아예 뜨지 않고, 그러면 통합 테스트 전체가
 * 무너진다. 실제로 한 번 그렇게 깨졌고 브랜치 빌드에서야 드러났다.
 *
 * <p>이 테스트는 MQTT 설정을 전혀 올리지 않는다. 누군가 장치 서비스에 {@code potner.mqtt.*}
 * 의존을 다시 넣으면 여기서 먼저 실패한다.
 */
class DeviceConfigurationTest {

    @Test
    void batteryServiceIsCreatedWithoutAnyMqttConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeviceConfiguration.class, StubDependencies.class)
                .withPropertyValues("potner.device.battery-reporter-type=JETSON_ORIN")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RobotBatteryService.class);
                    assertThat(context.getBean(DeviceProperties.class).batteryReporterType())
                            .isEqualTo(IotDeviceType.JETSON_ORIN);
                });
    }

    @Test
    void reporterTypeCanBeMovedToTheRaspberryByConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeviceConfiguration.class, StubDependencies.class)
                .withPropertyValues("potner.device.battery-reporter-type=RASPBERRY_PI")
                .run(context -> assertThat(context.getBean(DeviceProperties.class)
                        .batteryReporterType()).isEqualTo(IotDeviceType.RASPBERRY_PI));
    }

    @Test
    void unknownReporterTypeFailsAtStartupInsteadOfEveryMessage() {
        // 문자열로 받아 valueOf 를 부르면 메시지마다 예외가 나며 로그만 쌓인다.
        new ApplicationContextRunner()
                .withUserConfiguration(DeviceConfiguration.class, StubDependencies.class)
                .withPropertyValues("potner.device.battery-reporter-type=SOMETHING_ELSE")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class StubDependencies {

        @Bean
        public IotDeviceRepository iotDeviceRepository() {
            return mock(IotDeviceRepository.class);
        }

        @Bean
        public Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        public RobotBatteryService robotBatteryService(
                IotDeviceRepository iotDeviceRepository,
                DeviceProperties deviceProperties,
                Clock clock
        ) {
            return new RobotBatteryService(iotDeviceRepository, deviceProperties, clock);
        }
    }
}
