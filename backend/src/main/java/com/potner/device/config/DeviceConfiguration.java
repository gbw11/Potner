package com.potner.device.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 조건 없이 로드된다. 브로커가 꺼져 있어도 장치 도메인 서비스는 떠야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeviceProperties.class)
public class DeviceConfiguration {
}
